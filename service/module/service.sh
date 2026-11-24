#!/system/bin/sh
MODDIR=${0%/*}
BASE=/data/adb/smartvowificall_ims
APK="$MODDIR/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk"
. "$MODDIR/app-install.sh"
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2;done
mkdir -p "$BASE/run"; chmod 0700 "$BASE" "$BASE/run"
SERVICE_LOCK="$BASE/run/service.lock"
proc_start() {
 [ -r "/proc/$1/stat" ] || return 1
 IFS= read -r record < "/proc/$1/stat" || return 1
 rest=${record##*) }
 set -- $rest
 [ "$#" -ge 20 ] || return 1
 shift 19
 printf '%s' "$1"
}
# Single-instance guard prevents duplicate watchdogs on module reload/restart.
if ! mkdir "$SERVICE_LOCK" 2>/dev/null; then
 if [ -f "$SERVICE_LOCK/owner" ]; then
  read -r lpid lstart < "$SERVICE_LOCK/owner" 2>/dev/null || { lpid=; lstart=; }
  if [ -n "$lpid" ] && [ "$(proc_start "$lpid" 2>/dev/null || :)" = "$lstart" ]; then
   exit 0
  fi
  rm -rf "$SERVICE_LOCK"
  mkdir "$SERVICE_LOCK" 2>/dev/null || exit 0
 else
  exit 0
 fi
fi
(umask 077; printf '%s %s\n' "$$" "$(proc_start "$$" 2>/dev/null || echo 0)" > "$SERVICE_LOCK/owner")
APP_SYNCED=0
# No RECORD_AUDIO grant and no audio connections in the registration-only workflow.
network_pid=
network_start=
DAEMON_DIR=/data/local/tmp/smartvowificall
DAEMON_SOCK="$DAEMON_DIR/root-daemon.sock"
app_uid() {
 stat -c '%u' /data/data/com.smartvowificall.ims 2>/dev/null || echo ""
}
stop_root_daemon() {
 if [ -f "$BASE/run/root-daemon.pid" ] && [ -f "$BASE/run/root-daemon.owner" ]; then
  read -r dpid < "$BASE/run/root-daemon.pid" 2>/dev/null || dpid=
  read -r dopid dostart < "$BASE/run/root-daemon.owner" 2>/dev/null || { dopid=; dostart=; }
  if [ -n "$dpid" ] && [ "$dpid" = "$dopid" ] && [ "$(proc_start "$dpid" 2>/dev/null || :)" = "$dostart" ]; then
   kill -TERM "$dpid" 2>/dev/null || :
  fi
 fi
 rm -f "$BASE/run/root-daemon.pid" "$BASE/run/root-daemon.owner" "$DAEMON_SOCK"
}
start_root_daemon() {
 uid=$(app_uid)
 mkdir -p "$DAEMON_DIR" "$BASE/run"
 chmod 0711 "$DAEMON_DIR"
 if [ -f "$BASE/run/root-daemon.pid" ] && [ -f "$BASE/run/root-daemon.owner" ]; then
  read -r dpid < "$BASE/run/root-daemon.pid" 2>/dev/null || dpid=
  read -r dopid dostart < "$BASE/run/root-daemon.owner" 2>/dev/null || { dopid=; dostart=; }
  if [ -n "$dpid" ] && [ "$dpid" = "$dopid" ] && [ "$(proc_start "$dpid" 2>/dev/null || :)" = "$dostart" ] && [ -S "$DAEMON_SOCK" ]; then
   if [ -n "$uid" ] && [ "$(stat -c '%u:%g' "$DAEMON_SOCK" 2>/dev/null || :)" != "0:$uid" ]; then
    chown "0:$uid" "$DAEMON_SOCK" 2>/dev/null || :
    chmod 0660 "$DAEMON_SOCK" 2>/dev/null || :
   fi
   return 0
  fi
 fi
 CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootDaemonMain "$DAEMON_SOCK" "${uid:-}" >>"$BASE/run/root-daemon.log" 2>&1 &
 dpid=$!
 dstart=$(proc_start "$dpid" 2>/dev/null || :)
 if [ -n "$dstart" ]; then
  (umask 077; printf '%s\n' "$dpid" > "$BASE/run/root-daemon.pid"; printf '%s %s\n' "$dpid" "$dstart" > "$BASE/run/root-daemon.owner")
 fi
}
publisher_running() {
 [ -n "$network_pid" ] && [ -n "$network_start" ] || return 1
 [ "$(proc_start "$network_pid" 2>/dev/null)" = "$network_start" ]
}
stop_publisher() {
 if publisher_running; then kill -TERM "$network_pid" 2>/dev/null || :;fi
 network_pid=
 network_start=
}
stop_sim_auth() {
 if [ -f "$BASE/run/sim-auth.pid" ] && [ -f "$BASE/run/sim-auth.owner" ]; then
  read -r spid < "$BASE/run/sim-auth.pid" 2>/dev/null || spid=
  read -r sopid sostart < "$BASE/run/sim-auth.owner" 2>/dev/null || { sopid=; sostart=; }
  if [ -n "$spid" ] && [ "$spid" = "$sopid" ] && [ "$(proc_start "$spid" 2>/dev/null || :)" = "$sostart" ]; then
   kill -TERM "$spid" 2>/dev/null || :
  fi
 fi
 rm -f "$BASE/run/sim-auth.pid" "$BASE/run/sim-auth.owner" "$BASE/run/sim-aka.sock"
}
start_sim_auth() {
 if [ -f "$BASE/run/sim-auth.pid" ] && [ -f "$BASE/run/sim-auth.owner" ]; then
  read -r spid < "$BASE/run/sim-auth.pid" 2>/dev/null || spid=
  read -r sopid sostart < "$BASE/run/sim-auth.owner" 2>/dev/null || { sopid=; sostart=; }
  if [ -n "$spid" ] && [ "$spid" = "$sopid" ] && [ "$(proc_start "$spid" 2>/dev/null || :)" = "$sostart" ] && [ -S "$BASE/run/sim-aka.sock" ]; then
   return 0
  fi
 fi
 mkdir -p "$BASE/run"; chmod 0700 "$BASE/run"
 rm -f "$BASE/run/sim-aka.sock"
 CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootSimAuthMain "$BASE/run/sim-aka.sock" >>"$BASE/run/sim-auth.log" 2>&1 &
 spid=$!
 sstart=$(proc_start "$spid" 2>/dev/null || :)
 if [ -n "$sstart" ]; then
  (umask 077; printf '%s\n' "$spid" > "$BASE/run/sim-auth.pid"; printf '%s %s\n' "$spid" "$sstart" > "$BASE/run/sim-auth.owner")
 fi
}
stop_helpers() {
 stop_publisher
 stop_sim_auth
 stop_root_daemon
 rm -f "$SERVICE_LOCK/owner"
 rmdir "$SERVICE_LOCK" 2>/dev/null || :
}
trap stop_helpers EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
start_publisher() {
 if ! publisher_running; then
  CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootNetworkMain watch >/dev/null 2>&1 &
  network_pid=$!
  network_start=$(proc_start "$network_pid" 2>/dev/null || :)
 fi
}
restore_owned() {
 [ -f "$APK" ] || return 1
 CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootIdentityMain ims-restore-all >/dev/null 2>&1 || return 1
 for p in "$BASE/run/"*.pid; do
  [ -f "$p" ] || continue
  id=${p##*/};id=${id%.pid}
  [ "$id" != "sim-auth" ] && [ "$id" != "root-daemon" ] || continue
  sh "$MODDIR/control.sh" stop "$id" >/dev/null 2>&1 || return 1
 done
 stop_sim_auth
 stop_root_daemon
 sh "$MODDIR/clash-control.sh" stop >/dev/null 2>&1 || return 1
 CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootIdentityMain radio-release-all >/dev/null 2>&1 || return 1
 rm -f "$BASE/rollback-pending"
}
watchdog_ticks=0
repair_ticks=0
while :; do
 if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || [ -e "$BASE/rollback-pending" ]; then
  mkdir -p "$BASE";chmod 0700 "$BASE"
  (umask 077;printf 'RESTORE_REQUIRED\n' > "$BASE/rollback-pending")
  # A call or unknown call state defers rollback. Never kill an engine first.
  if restore_owned; then
   am stopservice --user 0 -n com.smartvowificall.ims/.PolicyService >/dev/null 2>&1
   [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || continue
   exit 0
  fi
 else
  if [ "$APP_SYNCED" != 1 ]; then
   nv_result=0
   nv_install_app "$MODDIR" || nv_result=$?
   mkdir -p "$BASE";chmod 0700 "$BASE"
   (umask 077;printf '%s\n' "$NV_APP_RESULT" > "$BASE/app-install.status")
   if [ "$nv_result" -ne 0 ]; then
    # Permanent signature/downgrade errors preserve the old application. A
    # temporary PackageManager/call-state failure retries without starting IMS.
    [ "$nv_result" -ne 2 ] || exit 1
    sleep 10
    continue
   fi
   APP_SYNCED=1
   pm grant com.smartvowificall.ims android.permission.READ_PHONE_STATE >/dev/null 2>&1
   pm grant com.smartvowificall.ims android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
  fi
  # The app owns event-driven policy. Only recover a missing process, not wake it every tick.
  if [ "$watchdog_ticks" -le 0 ]; then
   if ! pidof com.smartvowificall.ims >/dev/null 2>&1; then
    cmd activity start-foreground-service --user 0 -n com.smartvowificall.ims/.PolicyService >/dev/null 2>&1
   fi
   watchdog_ticks=6
  fi
  watchdog_ticks=$((watchdog_ticks-1))
  start_root_daemon
  has_engine=0
  for p in "$BASE/run/"*.pid; do
   [ -f "$p" ] || continue
   id=${p##*/};id=${id%.pid}
   [ "$id" != "sim-auth" ] && [ "$id" != "root-daemon" ] || continue
   has_engine=1
   if [ "$repair_ticks" -le 0 ]; then
    sh "$MODDIR/control.sh" repair "$id" >/dev/null 2>&1 || :
   fi
  done
  if [ "$has_engine" = 1 ]; then
   # Keep the engine's <=15s freshness contract and cross-baseband SIM AKA daemon alive while in use.
   start_publisher
   start_sim_auth
   sleep_sec=5
  else
   stop_publisher
   stop_sim_auth
   sleep_sec=25
  fi
  if [ "$repair_ticks" -le 0 ]; then repair_ticks=6;fi
  repair_ticks=$((repair_ticks-1))
 fi
 sleep "${sleep_sec:-5}"
done
