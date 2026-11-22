#!/system/bin/sh
[ "$(getprop ro.build.version.sdk)" -ge 37 ] || abort "NewVoWiFi requires Android 17 / API 37."
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || abort "NewVoWiFi requires arm64-v8a."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/vowifi-engine-arm64" 0 0 0755
set_perm "$MODPATH/control.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/app-install.sh" 0 0 0644
. "$MODPATH/app-install.sh"
if [ "${BOOTMODE:-false}" = true ]; then
 nv_result=0
 nv_install_app "$MODPATH" || nv_result=$?
 case "$nv_result" in
  0) ui_print "- NewVoWiFi APK installed/verified; existing configuration retained.";;
  2) abort "NewVoWiFi APK upgrade rejected ($NV_APP_RESULT). Existing app data was retained.";;
  *) ui_print "! APK installation deferred ($NV_APP_RESULT). It will retry automatically after reboot.";;
 esac
else
 ui_print "- NewVoWiFi APK will be installed automatically on boot; no separate APK installation is needed."
fi
ui_print "- Reboot to activate the updated module and complete APK verification."
