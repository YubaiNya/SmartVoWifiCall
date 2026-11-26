#!/system/bin/sh
set -eu
MODDIR=${0%/*}
BASE=/data/adb/smartvowificall_ims
APP_PROCESS=/system/bin/app_process
action=${1:-}
iccid=${2:-}
fail() { printf '{"ok":false,"code":"%s"}\n' "$1"; exit "${2:-1}"; }
case "$iccid" in ''|*[!0-9]*) fail INVALID_ID 64;; esac
[ ${#iccid} -ge 10 ] && [ ${#iccid} -le 32 ] || fail INVALID_ID 64
if [ "$action" != status ]; then
 mkdir -p "$BASE/config" "$BASE/run" "$BASE/logs"
 chmod 0700 "$BASE" "$BASE/config" "$BASE/run" "$BASE/logs"
fi
umask 077
pidfile="$BASE/run/$iccid.pid"
ownerfile="$BASE/run/$iccid.owner"
socketfile="$BASE/run/$iccid.sockets"
uidfile="$BASE/run/$iccid.uid"
lock="$BASE/run/$iccid.lock"
CLASH0_NETNS_OWNER="$BASE/clash/netns.owner"
CLASH1_NETNS_OWNER="$BASE/clash1/netns.owner"
launched=0
proc_start() {
 [ -r "/proc/$1/stat" ] || return 1
 record=$(cat "/proc/$1/stat") || return 1
 rest=${record##*) }
 set -- $rest
 [ "$#" -ge 20 ] || return 1
 shift 19
 printf '%s' "$1"
}
lifecycle_reason=UNSPECIFIED
lifecycle_slot=-1
case "$action" in
 start) lifecycle_reason=${4:-UNSPECIFIED};lifecycle_slot=${5:--1};;
 stop) lifecycle_reason=${3:-UNSPECIFIED};lifecycle_slot=${4:--1};;
esac
case "$lifecycle_reason" in ''|*[!A-Z_]*) lifecycle_reason=UNSPECIFIED;; esac
[ ${#lifecycle_reason} -le 64 ] || lifecycle_reason=UNSPECIFIED
case "$lifecycle_slot" in 0|1|2|3|4|5|6|7) :;; *) lifecycle_slot=-1;; esac
# Mutating lifecycle only; status/repair remain quiet and read-only. Each SIM's
# existing controller lock also serializes this bounded journal's rotation.
trace_control() {
 (
  set +e
  umask 077
  trace_dir="$BASE/logs/$iccid"
  mkdir -p "$trace_dir" || exit 0
  chmod 0700 "$trace_dir" || exit 0
  trace_file="$trace_dir/control-lifecycle.jsonl"
  if [ -f "$trace_file" ]; then
   trace_size=$(wc -c < "$trace_file")
   if [ "$trace_size" -ge 65536 ]; then mv -f "$trace_file" "$trace_file.1" || exit 0;fi
  fi
  trace_time=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null)
  printf '{"schema":"smartvowificall.lifecycle.v1","ts":"%s","component":"control","event":"%s","reason":"%s","slot":%s,"pid":%s,"startTicks":%s}\n' \
   "$trace_time" "$1" "$lifecycle_reason" "$lifecycle_slot" "${2:-0}" "${3:-0}" >> "$trace_file"
 ) || :
}
locked=0
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
 i=$((i+1));[ "$i" -lt 8 ] || fail ENGINE_BUSY
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
 case "$commandline" in *"$MODDIR/bin/vowifi-engine-arm64 --iccid=$iccid "*"--config=$BASE/config/$iccid.json "*) kill -0 "$pid" 2>/dev/null;; *) return 1;; esac
}
builtin_clash_tun() {
 [ -f "$BASE/config/$iccid.json" ] || return 1
 if grep -Eq '"transport"[[:space:]]*:[[:space:]]*"iface:wfctun0"' "$BASE/config/$iccid.json"; then
  printf 'wfctun0\n'; return 0
 fi
 if grep -Eq '"transport"[[:space:]]*:[[:space:]]*"iface:wfctun1"' "$BASE/config/$iccid.json"; then
  printf 'wfctun1\n'; return 0
 fi
 return 1
}
clash_netns_path_for() {
 owner_file="$CLASH0_NETNS_OWNER"
 [ "${1:-wfctun0}" = "wfctun1" ] && owner_file="$CLASH1_NETNS_OWNER"
 [ -f "$owner_file" ] || return 1
 read -r nspid nsstart nsid < "$owner_file" || return 1
 case "${nspid:-}:${nsstart:-}" in *[!0-9:]*|:*) return 1;; esac
 [ -n "${nsid:-}" ] || return 1
 [ "$(proc_start "$nspid" 2>/dev/null || :)" = "$nsstart" ] || return 1
 live_ns=$(readlink "/proc/$nspid/ns/net" 2>/dev/null || :)
 root_ns=$(readlink /proc/1/ns/net 2>/dev/null || readlink /proc/$$/ns/net 2>/dev/null || :)
 [ -n "$live_ns" ] && [ "$live_ns" = "$nsid" ] && [ "$live_ns" != "$root_ns" ] || return 1
 printf '/proc/%s/ns/net\n' "$nspid"
}
socket_token() { stat -c '%d:%i:%Z' "$1" 2>/dev/null; }
socket_kernel_inode() {
 path=$1
 table=${2:-}
 if [ -n "$table" ]; then
  [ -r "$table" ] || return 1
  while read -r number refs protocol flags type state inode name; do
   if [ "${name:-}" = "$path" ]; then printf '%s\n' "$inode"; return 0; fi
  done < "$table"
  return 1
 fi
 if [ -r /proc/net/unix ]; then
  while read -r number refs protocol flags type state inode name; do
   if [ "${name:-}" = "$path" ]; then printf '%s\n' "$inode"; return 0; fi
  done < /proc/net/unix
 fi
 for tdev in wfctun0 wfctun1; do
  ns=$(clash_netns_path_for "$tdev" 2>/dev/null || :)
  if [ -n "$ns" ]; then
   nsenter --net="$ns" -- sh -c '
    while read -r number refs protocol flags type state inode name; do
     if [ "${name:-}" = "$1" ]; then printf "%s\n" "$inode"; exit 0; fi
    done < /proc/net/unix
    exit 1
   ' _ "$path" 2>/dev/null && return 0
  fi
 done
 return 1
}
pid_owns_socket() {
 owned_pid || return 1
 inode=$(socket_kernel_inode "$1" "/proc/$pid/net/unix") || return 1
 for fd in "/proc/$pid/fd/"*; do
  if [ "$(readlink "$fd" 2>/dev/null || :)" = "socket:[$inode]" ]; then return 0; fi
 done
 return 1
}
cleanup_recorded_sockets() {
 [ -f "$socketfile" ] || return 0
 while read -r suffix token; do
  case "$suffix" in sock|audio.sock) :;; *) return 1;; esac
  socket="/data/local/tmp/vowifi/$iccid.$suffix"
  [ -S "$socket" ] || continue
  [ "$(socket_token "$socket")" = "$token" ] || return 1
  # A currently alive UNIX endpoint may belong to another process; never unlink it.
  if socket_kernel_inode "$socket" >/dev/null 2>&1; then return 1; fi
  rm -f "$socket"
 done < "$socketfile"
}
record_and_grant() {
 uid=$1
 owned_pid || return 1
 # Merge new audio endpoint proof when it appears after the control endpoint.
 temp="$socketfile.tmp.$$"
 [ ! -f "$socketfile" ] || cp "$socketfile" "$temp"
 : >> "$temp"
 for suffix in sock audio.sock; do
  socket="/data/local/tmp/vowifi/$iccid.$suffix"
  if [ -S "$socket" ]; then
   pid_owns_socket "$socket" || { rm -f "$temp"; return 1; }
   # chown/chmod update ctime: apply them before sealing the ownership token.
   if [ "$(stat -c '%u:%g' "$socket")" != "0:$uid" ]; then chown "0:$uid" "$socket";fi
   if [ "$(stat -c '%a' "$socket")" != 660 ]; then chmod 0660 "$socket";fi
   token=$(socket_token "$socket") || { rm -f "$temp"; return 1; }
   # Existing entries are replaced without accepting an unproved pathname.
   filtered="$temp.next"
   while read -r oldsuffix oldtoken; do
    [ "$oldsuffix" = "$suffix" ] || printf '%s %s\n' "$oldsuffix" "$oldtoken"
   done < "$temp" > "$filtered"
   printf '%s %s\n' "$suffix" "$token" >> "$filtered"
   mv "$filtered" "$temp"
  fi
 done
 mv "$temp" "$socketfile"
}
stop_owned() {
 if owned_pid; then
  trace_control STOP_TERM "$pid" "$ownerstart"
  kill -TERM "$pid"
  i=0
  while owned_pid && [ "$i" -lt 8 ]; do sleep 1;i=$((i+1));done
  if owned_pid; then trace_control STOP_KILL "$pid" "$ownerstart";kill -KILL "$pid";sleep 1;fi
  owned_pid && return 1
  trace_control STOPPED "$pid" "$ownerstart"
 fi
 cleanup_recorded_sockets || return 1
 rm -f "$pidfile" "$ownerfile" "$socketfile" "$uidfile"
}
check_tun_egress() {
 egress=$(CLASSPATH="$MODDIR/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk" "$APP_PROCESS" /system/bin com.smartvowificall.ims.root.RootIdentityMain check-egress "$BASE/config/$iccid.json" 2>/dev/null) || return 1
 case "$egress" in *'"ok":true'*) return 0;; *) return 1;; esac
}
SIM_AUTH_SOCK="$BASE/run/sim-aka.sock"
SIM_AUTH_PIDFILE="$BASE/run/sim-auth.pid"
SIM_AUTH_OWNERFILE="$BASE/run/sim-auth.owner"
sim_auth_running() {
 [ -f "$SIM_AUTH_PIDFILE" ] && [ -f "$SIM_AUTH_OWNERFILE" ] || return 1
 read -r spid < "$SIM_AUTH_PIDFILE" || return 1
 read -r sopid sostart < "$SIM_AUTH_OWNERFILE" || return 1
 case "$spid:$sopid:$sostart" in *[!0-9:]*|:*) return 1;; esac
 [ "$spid" = "$sopid" ] || return 1
 [ "$(proc_start "$spid" 2>/dev/null || :)" = "$sostart" ] && kill -0 "$spid" 2>/dev/null
}
ensure_sim_auth() {
 if sim_auth_running && [ -S "$SIM_AUTH_SOCK" ]; then return 0; fi
 rm -f "$SIM_AUTH_SOCK"
 setsid env CLASSPATH="$MODDIR/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk" "$APP_PROCESS" /system/bin com.smartvowificall.ims.root.RootSimAuthMain "$SIM_AUTH_SOCK" >>"$BASE/run/sim-auth.log" 2>&1 </dev/null &
 spid=$!
 sstart=$(proc_start "$spid" 2>/dev/null || :)
 if [ -n "$sstart" ]; then
  printf '%s\n' "$spid" > "$SIM_AUTH_PIDFILE"
  printf '%s %s\n' "$spid" "$sstart" > "$SIM_AUTH_OWNERFILE"
 fi
 k=0
 while [ "$k" -lt 10 ]; do
  [ -S "$SIM_AUTH_SOCK" ] && return 0
  k=$((k+1)); sleep 0.2 2>/dev/null || sleep 1
 done
 return 0
}
case "$action" in
 status)
  if owned_pid; then printf '{"ok":true,"code":"ENGINE_RUNNING","running":true}\n';exit 0;fi
  [ -f "$ownerfile" ] || fail ENGINE_OWNERSHIP_UNKNOWN
  read -r oldpid oldstart < "$ownerfile" || fail ENGINE_OWNERSHIP_UNKNOWN
  case "$oldpid:$oldstart" in *[!0-9:]*|:*|*:) fail ENGINE_OWNERSHIP_UNKNOWN;; esac
  if [ -f "$pidfile" ]; then
   read -r currentpid < "$pidfile" || fail ENGINE_OWNERSHIP_UNKNOWN
   [ "$currentpid" = "$oldpid" ] || fail ENGINE_OWNERSHIP_UNKNOWN
  fi
  currentstart=$(proc_start "$oldpid" 2>/dev/null || :)
  if [ -n "$currentstart" ]; then
   [ "$currentstart" != "$oldstart" ] || fail ENGINE_OWNERSHIP_UNKNOWN
  else
   [ ! -d "/proc/$oldpid" ] || fail ENGINE_OWNERSHIP_UNKNOWN
  fi
  printf '{"ok":true,"code":"ENGINE_STOPPED","running":false}\n'
  ;;
 start)
  [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] && [ ! -e "$BASE/rollback-pending" ] || fail MODULE_DISABLED
  uid=${3:-};case "$uid" in ''|*[!0-9]*) fail INVALID_UID 64;; esac
  check_tun_egress || fail TUN_EGRESS_UNAVAILABLE
  trace_control START_REQUEST
  if ! owned_pid; then
   cleanup_recorded_sockets || fail SOCKET_OWNERSHIP_UNKNOWN
   # Do not adopt a live or unrecorded socket belonging to the original application.
   [ ! -S "/data/local/tmp/vowifi/$iccid.sock" ] && [ ! -S "/data/local/tmp/vowifi/$iccid.audio.sock" ] || fail SOCKET_OWNERSHIP_UNKNOWN
   [ -s "$BASE/config/$iccid.json" ] || fail CONFIG_MISSING
   mkdir -p "$BASE/logs/$iccid" /data/local/tmp/vowifi
   chmod 0700 "$BASE/logs/$iccid"
   chmod o+x /data/local/tmp/vowifi
   ensure_sim_auth || :
   if btun=$(builtin_clash_tun 2>/dev/null); then
    ns=$(clash_netns_path_for "$btun" 2>/dev/null || :)
    [ -n "$ns" ] || fail TUN_EGRESS_UNAVAILABLE
    setsid nsenter --net="$ns" -- unshare -m -- sh -c '
     mount -o private none / || exit 1
     mount -o private none /sys || exit 1
     mount -t sysfs sysfs /sys || exit 1
     exec "$@"
    ' _ "$MODDIR/bin/vowifi-engine-arm64" --iccid="$iccid" --config="$BASE/config/$iccid.json" --pid-file="$pidfile" --sim-mode=auto --sim-socket="$SIM_AUTH_SOCK" --log-dir="$BASE/logs/$iccid" >>"$BASE/run/$iccid.launcher.log" 2>&1 </dev/null &
   else
    setsid "$MODDIR/bin/vowifi-engine-arm64" --iccid="$iccid" --config="$BASE/config/$iccid.json" --pid-file="$pidfile" --sim-mode=auto --sim-socket="$SIM_AUTH_SOCK" --log-dir="$BASE/logs/$iccid" >>"$BASE/run/$iccid.launcher.log" 2>&1 </dev/null &
   fi
   pid=$!
   printf '%s\n' "$pid" > "$pidfile"
   start=$(proc_start "$pid" 2>/dev/null || :)
   [ -n "$start" ] || fail ENGINE_START_FAILED
   printf '%s %s\n' "$pid" "$start" > "$ownerfile"
   launched=1
  fi
  printf '%s\n' "$uid" > "$uidfile"
  i=0
  while [ "$i" -lt 15 ]; do
   if owned_pid && [ -S "/data/local/tmp/vowifi/$iccid.sock" ]; then
    if record_and_grant "$uid"; then
     [ "$launched" != 1 ] || trace_control START_READY "$pid" "$ownerstart"
     printf '{"ok":true,"code":"ENGINE_READY"}\n';exit 0
    fi
    break
   fi
   i=$((i+1));sleep 1
  done
  if [ "$launched" = 1 ]; then stop_owned || :;fi
  fail ENGINE_START_FAILED
  ;;
 repair)
  [ -f "$uidfile" ] || fail OWNER_UID_MISSING
  read -r uid < "$uidfile"
  case "$uid" in ''|*[!0-9]*) fail INVALID_UID;; esac
  owned_pid || fail ENGINE_NOT_RUNNING
  record_and_grant "$uid" || fail SOCKET_OWNERSHIP_UNKNOWN
  ;;
 stop)
  trace_control STOP_REQUEST
  stop_owned || fail ENGINE_STOP_FAILED
  ;;
 *) fail INVALID_ACTION 64;;
esac
