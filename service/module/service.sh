#!/system/bin/sh
MODDIR=${0%/*}
BASE=/data/adb/newvowifi_ims
APK="$MODDIR/system/priv-app/NewVoWiFi/NewVoWiFi.apk"
. "$MODDIR/app-install.sh"
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2;done
APP_SYNCED=0
# No RECORD_AUDIO grant and no audio connections in the registration-only workflow.
network_pid=
network_start=
proc_start() {
 [ -r "/proc/$1/stat" ] || return 1
 record=$(cat "/proc/$1/stat") || return 1
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
}
trap stop_publisher EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
start_publisher() {
 if ! publisher_running; then
  CLASSPATH="$APK" /system/bin/app_process /system/bin com.newvowifi.ims.root.RootNetworkMain watch >/dev/null 2>&1 &
  network_pid=$!
  network_start=$(proc_start "$network_pid" 2>/dev/null || :)
 fi
}
restore_owned() {
 [ -f "$APK" ] || return 1
 CLASSPATH="$APK" /system/bin/app_process /system/bin com.newvowifi.ims.root.RootIdentityMain ims-restore-all >/dev/null 2>&1 || return 1
 for p in "$BASE/run/"*.pid; do
  [ -f "$p" ] || continue
  id=${p##*/};id=${id%.pid}
  sh "$MODDIR/control.sh" stop "$id" >/dev/null 2>&1 || return 1
 done
 rm -f "$BASE/rollback-pending"
}
while :; do
 if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || [ -e "$BASE/rollback-pending" ]; then
  mkdir -p "$BASE";chmod 0700 "$BASE"
  (umask 077;printf 'RESTORE_REQUIRED\n' > "$BASE/rollback-pending")
  # A call or unknown call state defers rollback. Never kill an engine first.
  if restore_owned; then
   am stopservice --user 0 -n com.newvowifi.ims/.PolicyService >/dev/null 2>&1
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
   pm grant com.newvowifi.ims android.permission.READ_PHONE_STATE >/dev/null 2>&1
   pm grant com.newvowifi.ims android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
  fi
  # The independent producer refreshes every 5 seconds even while lifecycle
  # operations block. The engine rejects observations older than 15 seconds.
  start_publisher
  # START_STICKY alone does not cover a crash loop or a stopped foreground service.
  cmd activity start-foreground-service --user 0 -n com.newvowifi.ims/.PolicyService >/dev/null 2>&1
  for p in "$BASE/run/"*.pid; do
   [ -f "$p" ] || continue
   id=${p##*/};id=${id%.pid}
   sh "$MODDIR/control.sh" repair "$id" >/dev/null 2>&1 || :
  done
 fi
 sleep 5
done
