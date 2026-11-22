#!/system/bin/sh
set -eu
MODDIR=${0%/*}
BASE=/data/adb/newvowifi_ims
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
socket_token() { stat -c '%d:%i:%Z' "$1" 2>/dev/null; }
socket_kernel_inode() {
 path=$1
 while read -r number refs protocol flags type state inode name; do
  if [ "${name:-}" = "$path" ]; then printf '%s\n' "$inode"; return 0; fi
 done < /proc/net/unix
 return 1
}
pid_owns_socket() {
 owned_pid || return 1
 inode=$(socket_kernel_inode "$1") || return 1
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
  kill -TERM "$pid"
  i=0
  while owned_pid && [ "$i" -lt 8 ]; do sleep 1;i=$((i+1));done
  if owned_pid; then kill -KILL "$pid";sleep 1;fi
  owned_pid && return 1
 fi
 cleanup_recorded_sockets || return 1
 rm -f "$pidfile" "$ownerfile" "$socketfile" "$uidfile"
}
check_tun_egress() {
 egress=$(CLASSPATH="$MODDIR/system/priv-app/NewVoWiFi/NewVoWiFi.apk" "$APP_PROCESS" /system/bin com.newvowifi.ims.root.RootIdentityMain check-egress "$BASE/config/$iccid.json" 2>/dev/null) || return 1
 case "$egress" in *'"ok":true'*) return 0;; *) return 1;; esac
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
  if ! owned_pid; then
   cleanup_recorded_sockets || fail SOCKET_OWNERSHIP_UNKNOWN
   # Do not adopt a live or unrecorded socket belonging to the original application.
   [ ! -S "/data/local/tmp/vowifi/$iccid.sock" ] && [ ! -S "/data/local/tmp/vowifi/$iccid.audio.sock" ] || fail SOCKET_OWNERSHIP_UNKNOWN
   [ -s "$BASE/config/$iccid.json" ] || fail CONFIG_MISSING
   mkdir -p "$BASE/logs/$iccid" /data/local/tmp/vowifi /data/adb/nvwims/run
   chmod 0700 /data/adb/nvwims /data/adb/nvwims/run "$BASE/logs/$iccid"
   chmod o+x /data/local/tmp/vowifi
   setsid "$MODDIR/bin/vowifi-engine-arm64" --iccid="$iccid" --config="$BASE/config/$iccid.json" --pid-file="$pidfile" --sim-mode=qrtr --log-dir="$BASE/logs/$iccid" >>"$BASE/run/$iccid.launcher.log" 2>&1 </dev/null &
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
    if record_and_grant "$uid"; then printf '{"ok":true,"code":"ENGINE_READY"}\n';exit 0;fi
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
  stop_owned || fail ENGINE_STOP_FAILED
  ;;
 *) fail INVALID_ACTION 64;;
esac
