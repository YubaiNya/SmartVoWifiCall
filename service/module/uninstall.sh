#!/system/bin/sh
MODDIR=${0%/*}
BASE=/data/adb/newvowifi_ims
APK="$MODDIR/system/priv-app/NewVoWiFi/NewVoWiFi.apk"
mkdir -p "$BASE";chmod 0700 "$BASE"
(umask 077;printf 'RESTORE_REQUIRED\n' > "$BASE/rollback-pending")
# Use the same current-subscription transaction as the app. No global reset or clear-all.
[ -f "$APK" ] || exit 1
i=0
until CLASSPATH="$APK" /system/bin/app_process /system/bin com.newvowifi.ims.root.RootIdentityMain ims-restore-all >/dev/null 2>&1; do
 i=$((i+1));[ "$i" -lt 8 ] || exit 1
 sleep 1
done
for p in "$BASE/run/"*.pid; do
 [ -f "$p" ] || continue
 id=${p##*/};id=${id%.pid}
 sh "$MODDIR/control.sh" stop "$id" >/dev/null 2>&1 || exit 1
done
am stopservice --user 0 -n com.newvowifi.ims/.PolicyService >/dev/null 2>&1
rm -f "$BASE/rollback-pending"
