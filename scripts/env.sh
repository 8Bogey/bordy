#!/usr/bin/env bash
# بيئة مشتركة لسكربتات بوردي (Git Bash على Windows).
# كل المسارات مطلقة لأن adb/java ليسا في PATH لهذه الجلسة.
set -euo pipefail

export PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/c/Users/BOGEY/.jdks/jbr-17.0.14}"
export ANDROID_HOME="${ANDROID_HOME:-/c/Users/BOGEY/AppData/Local/Android/Sdk}"
export ADB="${ADB:-$ANDROID_HOME/platform-tools/adb.exe}"
export PKG="${PKG:-com.bordy.app}"
export APK_DEBUG="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
export DB_NAME="${DB_NAME:-bordy.db}"
# يمنع Git Bash من تحويل /sdcard/... إلى مسار Windows
export MSYS_NO_PATHCONV=1
export PATH="$JAVA_HOME/bin:$PATH"

require_adb() {
  [[ -x "$ADB" ]] || { echo "adb غير موجود في $ADB (ثبّت platform-tools أو عيّن ADB)" >&2; exit 1; }
}

pick_device() {
  local devices
  devices="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -z "$devices" ]]; then
    echo "لا يوجد جهاز متصل (adb devices فارغ)." >&2
    exit 1
  fi
  if [[ $(wc -l <<<"$devices") -gt 1 ]]; then
    echo "أكثر من جهاز متصل؛ سيُستخدم الأول: $(head -1 <<<"$devices")" >&2
  fi
  head -1 <<<"$devices"
}

app_running() {
  "$ADB" -s "$1" shell pidof "$PKG" >/dev/null 2>&1
}
