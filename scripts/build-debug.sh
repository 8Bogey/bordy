#!/usr/bin/env bash
# بناء نسخة debug. أي وسائط إضافية تُمرَّر إلى gradlew.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug "$@"
ls -lh "$APK_DEBUG"
