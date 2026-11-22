#!/system/bin/sh
set -eu
MODDIR=${0%/*}
BASE=/data/adb/smartvowificall_ims
CLASH_DIR="$BASE/clash"
BIN="$MODDIR/bin/mihomo-arm64"
TUN_DEV=wfctun0
TUN_IP=198.19.0.1
NETNS_OWNER_FILE="$CLASH_DIR/netns.owner"
LEGACY_NETNS_FILE="$CLASH_DIR/netns"
LEGACY_NETNS_ID_FILE="$CLASH_DIR/netns.id"
ROUTE_TABLE=209
PREF_OIF=1050
PREF_SRC=1051
action=${1:-}
fail() { printf '{"ok":false,"code":"%s"}\n' "$1"; exit "${2:-1}"; }

if [ "$action" != status ]; then
 mkdir -p "$BASE" "$CLASH_DIR"
 chmod 0700 "$BASE" "$CLASH_DIR"
fi
umask 077

pidfile="$CLASH_DIR/clash.pid"
ownerfile="$CLASH_DIR/clash.owner"
lock="$CLASH_DIR/clash.lock"
runtime="$CLASH_DIR/runtime.yaml"
logfile="$CLASH_DIR/clash.log"
launched=0
locked=0

proc_start() {
 [ -r "/proc/$1/stat" ] || return 1
 record=$(cat "/proc/$1/stat") || return 1
 rest=${record##*) }
 set -- $rest
 [ "$#" -ge 20 ] || return 1
 shift 19
 printf '%s' "$1"
}

release_lock() {
 if [ "$locked" = 1 ]; then rm -f "$lock/owner"; rmdir "$lock" 2>/dev/null || :; fi
}
trap release_lock EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

if [ "$action" != status ]; then
 i=0
 while ! mkdir "$lock" 2>/dev/null; do
  if [ -f "$lock/owner" ]; then
   read -r lockpid lockstart < "$lock/owner" || :
   case "${lockpid:-}:${lockstart:-}" in *[!0-9:]*|:*) fail LOCK_STATE_INVALID;; esac
   live=$(proc_start "$lockpid" 2>/dev/null || :)
   if [ "$live" != "$lockstart" ]; then rm -f "$lock/owner"; rmdir "$lock" 2>/dev/null || :; continue; fi
  fi
  i=$((i+1)); [ "$i" -lt 8 ] || fail CLASH_BUSY
  sleep 1
 done
 locked=1
 printf '%s %s\n' "$$" "$(proc_start "$$")" > "$lock/owner"
fi

owned_pid() {
 [ -f "$pidfile" ] && [ -f "$ownerfile" ] || return 1
 read -r pid < "$pidfile" || return 1
 read -r ownerpid ownerstart < "$ownerfile" || return 1
 case "$pid:$ownerpid:$ownerstart" in *[!0-9:]*|:*) return 1;; esac
 [ "$pid" = "$ownerpid" ] || return 1
 [ "$(proc_start "$pid" 2>/dev/null || :)" = "$ownerstart" ] || return 1
 [ -r "/proc/$pid/cmdline" ] || return 1
 commandline=$(dd if="/proc/$pid/cmdline" bs=4096 count=1 2>/dev/null | tr '\000' ' ')
 case "$commandline" in *"$BIN -d $CLASH_DIR -f $runtime"*) kill -0 "$pid" 2>/dev/null;; *) return 1;; esac
}

owned_netns_pid() {
 [ -f "$NETNS_OWNER_FILE" ] || return 1
 read -r nspid nsstart nsid < "$NETNS_OWNER_FILE" || return 1
 case "${nspid:-}:${nsstart:-}" in *[!0-9:]*|:*) return 1;; esac
 [ -n "${nsid:-}" ] || return 1
 [ "$(proc_start "$nspid" 2>/dev/null || :)" = "$nsstart" ] || return 1
 [ -r "/proc/$nspid/cmdline" ] || return 1
 nscmd=$(dd if="/proc/$nspid/cmdline" bs=256 count=1 2>/dev/null | tr '\000' ' ')
 case "$nscmd" in *"sleep 2147483647"*) :;; *) return 1;; esac
 live_ns=$(readlink "/proc/$nspid/ns/net" 2>/dev/null || :)
 root_ns=$(readlink /proc/1/ns/net 2>/dev/null || readlink /proc/$$/ns/net 2>/dev/null || :)
 [ -n "$live_ns" ] && [ "$live_ns" = "$nsid" ] && [ "$live_ns" != "$root_ns" ] || return 1
 kill -0 "$nspid" 2>/dev/null
}

tun_in_root() {
 [ -r "/sys/class/net/$TUN_DEV/tun_flags" ] || return 1
 ip -4 addr show dev "$TUN_DEV" 2>/dev/null | grep -q "$TUN_IP" || return 1
}

tun_in_netns() {
 owned_netns_pid || return 1
 nsenter --net="/proc/$nspid/ns/net" -- sh -c '
  ip -4 addr show dev "$1" 2>/dev/null | grep -q "$2" &&
  ip -4 route show default dev "$1" 2>/dev/null | grep -q "default"
 ' _ "$TUN_DEV" "$TUN_IP"
}

remove_legacy_routes() {
 ip rule del oif "$TUN_DEV" lookup "$ROUTE_TABLE" priority "$PREF_OIF" 2>/dev/null || :
 ip rule del from "$TUN_IP/32" lookup "$ROUTE_TABLE" priority "$PREF_SRC" 2>/dev/null || :
 ip route flush table "$ROUTE_TABLE" 2>/dev/null || :
}

# Namespace isolation: mihomo stays in the root netns (using normal Android routing
# for outbound proxy connections), while wfctun0 is moved into an isolated netns
# anchored by a verified 0-CPU sleep process (/proc/<pid>/ns/net is visible across
# all Android/KernelSU mount namespaces without bind-mounts). Root netns routing
# tables and ip rules are never modified.
teardown_netns() {
 if owned_netns_pid; then
  kill -TERM "$nspid" 2>/dev/null || :
  j=0
  while kill -0 "$nspid" 2>/dev/null && [ "$j" -lt 10 ]; do
   j=$((j+1))
   sleep 0.1 2>/dev/null || sleep 1
  done
  if kill -0 "$nspid" 2>/dev/null; then kill -KILL "$nspid" 2>/dev/null || :; fi
 fi
 rm -f "$NETNS_OWNER_FILE" "$LEGACY_NETNS_ID_FILE"
 if [ -e "$LEGACY_NETNS_FILE" ]; then
  umount "$LEGACY_NETNS_FILE" 2>/dev/null || :
  rm -f "$LEGACY_NETNS_FILE"
 fi
 remove_legacy_routes
}

setup_netns() {
 teardown_netns
 rootns=$(readlink /proc/1/ns/net 2>/dev/null || readlink /proc/$$/ns/net 2>/dev/null || :)
 setsid unshare -n -- sleep 2147483647 >/dev/null 2>&1 </dev/null &
 nspid=$!
 j=0
 childns=""
 while [ "$j" -lt 30 ]; do
  childns=$(readlink "/proc/$nspid/ns/net" 2>/dev/null || :)
  if [ -n "$childns" ] && [ "$childns" != "$rootns" ]; then break; fi
  j=$((j+1))
  sleep 0.1 2>/dev/null || sleep 1
 done
 nsstart=$(proc_start "$nspid" 2>/dev/null || :)
 if [ -z "$childns" ] || [ "$childns" = "$rootns" ] || [ -z "$nsstart" ]; then
  kill -KILL "$nspid" 2>/dev/null || :
  teardown_netns
  return 1
 fi
 if ! ip link set dev "$TUN_DEV" netns "$nspid"; then
  kill -KILL "$nspid" 2>/dev/null || :
  teardown_netns
  return 1
 fi
 if ! nsenter --net="/proc/$nspid/ns/net" -- sh -c '
  ip link set dev lo up &&
  ip link set dev "$1" up &&
  ip addr replace "$2/30" dev "$1" &&
  ip route replace default dev "$1"
 ' _ "$TUN_DEV" "$TUN_IP"; then
  kill -KILL "$nspid" 2>/dev/null || :
  teardown_netns
  return 1
 fi
 tmp="$NETNS_OWNER_FILE.tmp.$$"
 printf '%s %s %s\n' "$nspid" "$nsstart" "$childns" > "$tmp"
 chmod 0600 "$tmp"
 mv "$tmp" "$NETNS_OWNER_FILE"
}

stop_owned() {
 if owned_pid; then
  kill -TERM "$pid" 2>/dev/null || :
  i=0
  while owned_pid && [ "$i" -lt 6 ]; do sleep 1; i=$((i+1)); done
  if owned_pid; then kill -KILL "$pid" 2>/dev/null || :; sleep 1; fi
  owned_pid && return 1
 fi
 teardown_netns
 rm -f "$pidfile" "$ownerfile"
}

case "$action" in
 status)
  running=false
  tunup=false
  nsid=""
  if owned_pid; then running=true; fi
  if [ "$running" = true ] && tun_in_netns; then
   tunup=true
   case "$nsid" in *[!A-Za-z0-9_:\[\]-]*) nsid="";; esac
  else
   nsid=""
  fi
  printf '{"ok":true,"code":"CLASH_STATUS","running":%s,"tunUp":%s,"device":"%s","netnsId":"%s"}\n' "$running" "$tunup" "$TUN_DEV" "$nsid"
  ;;
 start)
  [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] && [ ! -e "$BASE/rollback-pending" ] || fail MODULE_DISABLED
  [ -x "$BIN" ] || fail CLASH_BINARY_MISSING
  [ -s "$runtime" ] || fail CLASH_CONFIG_MISSING
  "$BIN" -t -d "$CLASH_DIR" -f "$runtime" >/dev/null 2>&1 || fail CLASH_CONFIG_INVALID
  if owned_pid; then
   if tun_in_netns; then
    case "$nsid" in *[!A-Za-z0-9_:\[\]-]*) nsid="";; esac
    printf '{"ok":true,"code":"CLASH_READY","device":"%s","netnsId":"%s"}\n' "$TUN_DEV" "$nsid"
    exit 0
   fi
   if ! tun_in_root; then
    stop_owned || fail CLASH_START_FAILED
   fi
  fi
  if ! owned_pid; then
   teardown_netns
   # Truncate oversized logs to keep storage bounded.
   if [ -f "$logfile" ] && [ "$(wc -c < "$logfile" 2>/dev/null || printf 0)" -gt 524288 ]; then
    : > "$logfile"
   fi
   setsid "$BIN" -d "$CLASH_DIR" -f "$runtime" >>"$logfile" 2>&1 </dev/null &
   pid=$!
   printf '%s\n' "$pid" > "$pidfile"
   start=$(proc_start "$pid" 2>/dev/null || :)
   [ -n "$start" ] || fail CLASH_START_FAILED
   printf '%s %s\n' "$pid" "$start" > "$ownerfile"
   launched=1
  fi
  i=0
  while [ "$i" -lt 15 ]; do
   if owned_pid && tun_in_root; then
    setup_netns || { if [ "$launched" = 1 ]; then stop_owned || :; fi; fail CLASH_ROUTE_FAILED; }
    outns=""
    if owned_netns_pid; then outns=$nsid; fi
    case "$outns" in *[!A-Za-z0-9_:\[\]-]*) outns="";; esac
    printf '{"ok":true,"code":"CLASH_READY","device":"%s","netnsId":"%s"}\n' "$TUN_DEV" "$outns"
    exit 0
   fi
   i=$((i+1)); sleep 1
  done
  if [ "$launched" = 1 ]; then stop_owned || :; fi
  fail CLASH_START_FAILED
  ;;
 stop)
  stop_owned || fail CLASH_STOP_FAILED
  printf '{"ok":true,"code":"CLASH_STOPPED"}\n'
  ;;
 *) fail INVALID_ACTION 64;;
esac
