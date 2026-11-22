#!/system/bin/sh
MODDIR=${0%/*}
BASE=/data/adb/newvowifi_ims
APK="$MODDIR/system/priv-app/NewVoWiFi/NewVoWiFi.apk"
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2;done
pm grant com.newvowifi.ims android.permission.READ_PHONE_STATE >/dev/null 2>&1
pm grant com.newvowifi.ims android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
# No RECORD_AUDIO grant and no audio connections in the registration-only workflow.
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
  # START_STICKY alone does not cover a crash loop or a stopped foreground service.
  cmd activity start-foreground-service --user 0 -n com.newvowifi.ims/.PolicyService >/dev/null 2>&1
  for p in "$BASE/run/"*.pid; do
   [ -f "$p" ] || continue
   id=${p##*/};id=${id%.pid}
   sh "$MODDIR/control.sh" repair "$id" >/dev/null 2>&1 || :
  done
 fi
 sleep 15
done
