#!/system/bin/sh
SDK=$(getprop ro.build.version.sdk)
[ "${SDK:-0}" -ge 34 ] || abort "SmartVoWifiCall requires Android 14-17 (API 34-37); detected API ${SDK:-unknown}."
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || abort "SmartVoWifiCall requires arm64-v8a."
if [ "$SDK" -lt 37 ]; then
 ui_print "- Android API $SDK detected (Android 14-16 compatibility mode enabled; run System Compatibility Self-Check in the app)."
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/vowifi-engine-arm64" 0 0 0755
set_perm "$MODPATH/bin/mihomo-arm64" 0 0 0755
set_perm "$MODPATH/control.sh" 0 0 0755
set_perm "$MODPATH/clash-control.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/app-install.sh" 0 0 0644
. "$MODPATH/app-install.sh"
if [ "${BOOTMODE:-false}" = true ]; then
 nv_result=0
 nv_install_app "$MODPATH" || nv_result=$?
 case "$nv_result" in
  0) ui_print "- SmartVoWifiCall APK installed/verified; existing configuration retained.";;
  2) abort "SmartVoWifiCall APK upgrade rejected ($NV_APP_RESULT). Existing app data was retained.";;
  *) ui_print "! APK installation deferred ($NV_APP_RESULT). It will retry automatically after reboot.";;
 esac
else
 ui_print "- SmartVoWifiCall APK will be installed automatically on boot; no separate APK installation is needed."
fi
ui_print "- Reboot to activate the updated module and complete APK verification."
