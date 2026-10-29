#!/usr/bin/env bash
# فحص ثم نشر Worker. الاستخدام:
#   scripts/deploy-worker.sh --dry-run   # بناء فقط بلا نشر
#   scripts/deploy-worker.sh             # نشر فعلي (يتطلب wrangler مسجّلاً)
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT/bordy-license"

npm test
npx tsc --noEmit

if [[ "${1:-}" == "--dry-run" ]]; then
  npx wrangler deploy --dry-run
else
  npx wrangler deploy
fi
