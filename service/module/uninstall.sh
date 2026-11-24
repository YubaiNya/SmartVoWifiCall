#!/system/bin/sh
MODDIR=${0%/*}
BASE=/data/adb/smartvowificall_ims
APK="$MODDIR/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk"
mkdir -p "$BASE";chmod 0700 "$BASE"
(umask 077;printf 'RESTORE_REQUIRED\n' > "$BASE/rollback-pending")
# Use the same current-subscription transaction as the app. No global reset or clear-all.
[ -f "$APK" ] || exit 1
i=0
until CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootIdentityMain ims-restore-all >/dev/null 2>&1; do
 i=$((i+1));[ "$i" -lt 8 ] || exit 1
 sleep 1
done
for p in "$BASE/run/"*.pid; do
 [ -f "$p" ] || continue
 id=${p##*/};id=${id%.pid}
 [ "$id" != "sim-auth" ] && [ "$id" != "root-daemon" ] || continue
 sh "$MODDIR/control.sh" stop "$id" >/dev/null 2>&1 || exit 1
done
if [ -f "$BASE/run/sim-auth.pid" ]; then
 read -r spid < "$BASE/run/sim-auth.pid" 2>/dev/null || spid=
 [ -n "$spid" ] && kill -TERM "$spid" 2>/dev/null || :
 rm -f "$BASE/run/sim-auth.pid" "$BASE/run/sim-auth.owner" "$BASE/run/sim-aka.sock"
fi
if [ -f "$BASE/run/root-daemon.pid" ]; then
 read -r dpid < "$BASE/run/root-daemon.pid" 2>/dev/null || dpid=
 [ -n "$dpid" ] && kill -TERM "$dpid" 2>/dev/null || :
 rm -f "$BASE/run/root-daemon.pid" "$BASE/run/root-daemon.owner" /data/local/tmp/vowifi/root-daemon.sock
fi
sh "$MODDIR/clash-control.sh" stop >/dev/null 2>&1 || exit 1
i=0
until CLASSPATH="$APK" /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootIdentityMain radio-release-all >/dev/null 2>&1; do
 i=$((i+1));[ "$i" -lt 12 ] || exit 1
 sleep 1
done
am stopservice --user 0 -n com.smartvowificall.ims/.PolicyService >/dev/null 2>&1
rm -f "$BASE/rollback-pending"
