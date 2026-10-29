#!/usr/bin/env bash
# فحص شجرة الواجهة (uiautomator) — المصدر الوحيد الموثوق للنصوص والإحداثيات.
# الاستخدام:
#   scripts/verify-ui.sh            # يطبع كل العقد غير الفارغة
#   scripts/verify-ui.sh "حفظ"      # يطبع فقط العقد التي تطابق النص/الوصف
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_adb
DEVICE="$(pick_device)"
FILTER="${1:-}"
OUT="${TMPDIR:-/tmp}/bordy-ui.xml"
REMOTE="/sdcard/bordy-ui.xml"

"$ADB" -s "$DEVICE" shell uiautomator dump "$REMOTE" >/dev/null
"$ADB" -s "$DEVICE" pull "$REMOTE" "$OUT" >/dev/null
"$ADB" -s "$DEVICE" shell rm -f "$REMOTE" >/dev/null 2>&1 || true

echo "dump: $OUT"
grep -oE '<node[^>]*>' "$OUT" | sed -E \
  -e 's/.*text="([^"]*)".*content-desc="([^"]*)".*class="([^"]*)".*bounds="([^"]*)".*/\4 | \1 | \2 | \3/' \
  | grep -v '^ *| *| *| ' \
  | { if [[ -n "$FILTER" ]]; then grep -- "$FILTER"; else cat; fi; }
