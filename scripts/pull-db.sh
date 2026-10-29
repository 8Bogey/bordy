#!/usr/bin/env bash
# سحب قاعدة البيانات بأمان (db + wal + shm معاً) بعد إيقاف التطبيق.
# الاستخدام: scripts/pull-db.sh [مجلد-الوجهة]
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_adb
DEVICE="$(pick_device)"
OUT="${1:-$PROJECT_ROOT/.local-db}"
mkdir -p "$OUT"

# لا تسحب قاعدة حيّة: قد ينتج زوج db/wal غير متسق يرفضه SQLite
"$ADB" -s "$DEVICE" shell am force-stop "$PKG"

for suffix in "" "-wal" "-shm"; do
  src="databases/${DB_NAME}${suffix}"
  dst="$OUT/${DB_NAME}${suffix}"
  if "$ADB" -s "$DEVICE" exec-out run-as "$PKG" cat "$src" >"$dst" 2>/dev/null && [[ -s "$dst" ]]; then
    echo "سُحب: $dst"
  else
    rm -f "$dst"
    echo "غير موجود: $src"
  fi
done

echo
echo "افتح النسخة محلياً بقراءة db و wal معاً — لا تحكم على البيانات من db وحده."
