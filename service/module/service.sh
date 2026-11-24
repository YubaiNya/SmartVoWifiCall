#!/system/bin/sh
MODDIR=${0%/*}
BASE=/data/adb/smartvowificall_ims
APK="$MODDIR/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk"
. "$MODDIR/app-install.sh"
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2;done
APP_SYNCED=0
# No RECORD_AUDIO grant and no audio connections in the registration-only workflow.
network_pid=
network_start=
proc_start() {
 [ -r "/proc/$1/stat" ] || return 1
 IFS= read -r record < "/proc/$1/stat" || return 1
 rest=${record##*) }
 set -- $rest
 [ "$#" -ge 20 ] || return 1
 shift 19
 printf '%s' "$1"
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
  [ "$id" != "sim-auth" ] || continue
  sh "$MODDIR/control.sh" stop "$id" >/dev/null 2>&1 || return 1
 done
 stop_sim_auth
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
  has_engine=0
  for p in "$BASE/run/"*.pid; do
   [ -f "$p" ] || continue
   id=${p##*/};id=${id%.pid}
   [ "$id" != "sim-auth" ] || continue
   has_engine=1
   if [ "$repair_ticks" -le 0 ]; then
    sh "$MODDIR/control.sh" repair "$id" >/dev/null 2>&1 || :
   fi
  done
  if [ "$has_engine" = 1 ]; then
   # Keep the engine's <=15s freshness contract and cross-baseband SIM AKA daemon alive while in use.
   start_publisher
   start_sim_auth
  else
   stop_publisher
   stop_sim_auth
  fi
  if [ "$repair_ticks" -le 0 ]; then repair_ticks=6;fi
  repair_ticks=$((repair_ticks-1))
 fi
 sleep 5
done
