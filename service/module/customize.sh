#!/system/bin/sh
[ "$(getprop ro.build.version.sdk)" -ge 37 ] || abort "NewVoWiFi requires Android 17 / API 37."
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || abort "NewVoWiFi requires arm64-v8a."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/vowifi-engine-arm64" 0 0 0755
set_perm "$MODPATH/control.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
