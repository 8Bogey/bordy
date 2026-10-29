#!/usr/bin/env bash
# تثبيت نسخة debug على الجهاز المتصل وتشغيلها.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_adb
DEVICE="$(pick_device)"

if [[ ! -f "$APK_DEBUG" ]]; then
  echo "لا يوجد APK؛ جارٍ البناء..."
  "$PROJECT_ROOT/scripts/build-debug.sh"
fi

"$ADB" -s "$DEVICE" install -r "$APK_DEBUG"
"$ADB" -s "$DEVICE" shell am force-stop "$PKG"
"$ADB" -s "$DEVICE" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "تم التثبيت والتشغيل على $DEVICE"
