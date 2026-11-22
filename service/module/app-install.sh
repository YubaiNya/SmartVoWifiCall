#!/system/bin/sh
# Shared by customize.sh and service.sh. Only PackageManager replaces the APK;
# no uninstall, pm clear, preference rewrite, or runtime-directory deletion.
NV_PM=${NV_PM:-/system/bin/pm}
NV_APP_PROCESS=${NV_APP_PROCESS:-/system/bin/app_process}

nv_apk_hash() {
 nv_hash=$(sha256sum "$1" 2>/dev/null) || return 1
 nv_hash=${nv_hash%% *}
 case "$nv_hash" in ''|*[!0-9a-f]*) return 1;; esac
 [ ${#nv_hash} -eq 64 ] || return 1
 printf '%s' "$nv_hash"
}

nv_installed_apk() {
 nv_listing=$("$NV_PM" path --user 0 com.newvowifi.ims 2>/dev/null) || return 1
 nv_path=
 nv_count=0
 while IFS= read -r nv_line; do
  case "$nv_line" in
   package:/*) nv_path=${nv_line#package:};nv_count=$((nv_count+1));;
   '') ;;
   *) return 1;;
  esac
 done <<EOF
$nv_listing
EOF
 [ "$nv_count" -eq 1 ] && [ -f "$nv_path" ] || return 1
 printf '%s' "$nv_path"
}

nv_install_app() {
 nv_apk="$1/system/priv-app/NewVoWiFi/NewVoWiFi.apk"
 NV_APP_RESULT=APP_APK_MISSING
 [ -s "$nv_apk" ] || return 2
 nv_expected=$(nv_apk_hash "$nv_apk") || { NV_APP_RESULT=APP_APK_HASH_FAILED;return 2; }
 nv_active=$(nv_installed_apk) || nv_active=
 if [ -n "$nv_active" ]; then
  nv_actual=$(nv_apk_hash "$nv_active") || nv_actual=
  if [ "$nv_actual" = "$nv_expected" ]; then
   NV_APP_RESULT=APP_CURRENT
   return 0
  fi
  # Replacement normally stops the old app process. Defer a live/unknown call
  # until idle (or the next boot), rather than interrupt it during module flashing.
  nv_idle=$(CLASSPATH="$nv_apk" "$NV_APP_PROCESS" /system/bin com.newvowifi.ims.root.RootIdentityMain install-check 2>/dev/null) || nv_idle=
  case "$nv_idle" in *'"ok":true'*) ;; *) NV_APP_RESULT=APP_WAIT_CALL_IDLE;return 10;; esac
 fi
 nv_size=$(wc -c < "$nv_apk") || { NV_APP_RESULT=APP_APK_READ_FAILED;return 2; }
 nv_size=$(printf '%s' "$nv_size" | tr -d '[:space:]')
 case "$nv_size" in ''|*[!0-9]*) NV_APP_RESULT=APP_APK_READ_FAILED;return 2;; esac
 nv_rc=0
 # Stream over stdin so system_server need not open a private /data/adb path.
 # -r retains the existing package data; never use -d to force a downgrade.
 nv_output=$("$NV_PM" install -r --user 0 -S "$nv_size" - < "$nv_apk" 2>&1) || nv_rc=$?
 if [ "$nv_rc" -ne 0 ] || ! printf '%s\n' "$nv_output" | tr -d '\r' | grep -qx 'Success'; then
  case "$nv_output" in
   *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES*) NV_APP_RESULT=APP_SIGNATURE_MISMATCH;return 2;;
   *INSTALL_FAILED_VERSION_DOWNGRADE*) NV_APP_RESULT=APP_DOWNGRADE_REJECTED;return 2;;
   *) NV_APP_RESULT=APP_INSTALL_FAILED;return 1;;
  esac
 fi
 nv_active=$(nv_installed_apk) || { NV_APP_RESULT=APP_VERIFY_FAILED;return 1; }
 nv_actual=$(nv_apk_hash "$nv_active") || { NV_APP_RESULT=APP_VERIFY_FAILED;return 1; }
 [ "$nv_actual" = "$nv_expected" ] || { NV_APP_RESULT=APP_VERIFY_FAILED;return 1; }
 NV_APP_RESULT=APP_INSTALLED
 return 0
}
