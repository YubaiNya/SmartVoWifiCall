#!/system/bin/sh
set -eu
MODDIR=${0%/*}
BASE=/data/adb/newvowifi_ims
CLASH_DIR="$BASE/clash"
BIN="$MODDIR/bin/mihomo-arm64"
TUN_DEV=wfctun0
TUN_IP=198.19.0.1
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

tun_ready() {
 [ -r "/sys/class/net/$TUN_DEV/tun_flags" ] || return 1
 ip -4 addr show dev "$TUN_DEV" 2>/dev/null | grep -q "$TUN_IP" || return 1
}

# Surgical policy routing: ONLY sockets explicitly bound to wfctun0 or sourced
# from 198.19.0.1 enter table 209. Main/default Android tables are never touched.
install_routes() {
 ip link set dev "$TUN_DEV" up 2>/dev/null || :
 ip route replace default dev "$TUN_DEV" table "$ROUTE_TABLE"
 ip rule del oif "$TUN_DEV" lookup "$ROUTE_TABLE" priority "$PREF_OIF" 2>/dev/null || :
 ip rule del from "$TUN_IP/32" lookup "$ROUTE_TABLE" priority "$PREF_SRC" 2>/dev/null || :
 ip rule add oif "$TUN_DEV" lookup "$ROUTE_TABLE" priority "$PREF_OIF"
 ip rule add from "$TUN_IP/32" lookup "$ROUTE_TABLE" priority "$PREF_SRC"
}

remove_routes() {
 ip rule del oif "$TUN_DEV" lookup "$ROUTE_TABLE" priority "$PREF_OIF" 2>/dev/null || :
 ip rule del from "$TUN_IP/32" lookup "$ROUTE_TABLE" priority "$PREF_SRC" 2>/dev/null || :
 ip route flush table "$ROUTE_TABLE" 2>/dev/null || :
}

stop_owned() {
 if owned_pid; then
  kill -TERM "$pid" 2>/dev/null || :
  i=0
  while owned_pid && [ "$i" -lt 6 ]; do sleep 1; i=$((i+1)); done
  if owned_pid; then kill -KILL "$pid" 2>/dev/null || :; sleep 1; fi
  owned_pid && return 1
 fi
 remove_routes
 rm -f "$pidfile" "$ownerfile"
}

case "$action" in
 status)
  running=false
  tunup=false
  if owned_pid; then running=true; fi
  if tun_ready; then tunup=true; fi
  printf '{"ok":true,"code":"CLASH_STATUS","running":%s,"tunUp":%s,"device":"%s"}\n' "$running" "$tunup" "$TUN_DEV"
  ;;
 start)
  [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] && [ ! -e "$BASE/rollback-pending" ] || fail MODULE_DISABLED
  [ -x "$BIN" ] || fail CLASH_BINARY_MISSING
  [ -s "$runtime" ] || fail CLASH_CONFIG_MISSING
  "$BIN" -t -d "$CLASH_DIR" -f "$runtime" >/dev/null 2>&1 || fail CLASH_CONFIG_INVALID
  if ! owned_pid; then
   remove_routes
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
   if owned_pid && tun_ready; then
    install_routes || { if [ "$launched" = 1 ]; then stop_owned || :; fi; fail CLASH_ROUTE_FAILED; }
    printf '{"ok":true,"code":"CLASH_READY","device":"%s"}\n' "$TUN_DEV"
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
