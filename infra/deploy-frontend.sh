#!/usr/bin/env bash
# 프론트엔드 정적 자산을 S3 에 sync 하고 CloudFront 캐시를 무효화한다.
# 빌드는 사전에 끝나 있어야 한다 (frontend/dist/ 존재).
#
# 사용법:
#   export STACK=stock-portfolio
#   export REGION=ap-northeast-2
#   VITE_API_BASE_URL=$API_URL VITE_API_KEY=$API_KEY \
#     npm --prefix frontend run build
#   ./infra/deploy-frontend.sh
#
# 환경변수 STACK / REGION 미설정 시 기본값 사용.

set -euo pipefail

STACK="${STACK:-stock-portfolio}"
REGION="${REGION:-ap-northeast-2}"

# 스크립트 위치 기준으로 repo root 계산 — cwd 무관하게 동작.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_DIR="$REPO_ROOT/frontend/dist"

if [[ ! -d "$DIST_DIR" ]]; then
  echo "ERROR: $DIST_DIR not found. Run 'npm --prefix frontend run build' first." >&2
  exit 1
fi

echo "==> Resolving stack outputs ($STACK in $REGION)"
BUCKET=$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendBucketName`].OutputValue' --output text)
DIST=$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendDistributionId`].OutputValue' --output text)

if [[ -z "$BUCKET" || "$BUCKET" == "None" || -z "$DIST" || "$DIST" == "None" ]]; then
  echo "ERROR: stack outputs FrontendBucketName / FrontendDistributionId not found." >&2
  exit 1
fi

echo "    BUCKET=$BUCKET"
echo "    DIST=$DIST"

echo "==> Sync non-hashed files (no-cache, --delete)"
aws s3 sync "$DIST_DIR" "s3://$BUCKET" \
  --delete \
  --exclude "assets/*" \
  --cache-control "no-cache, no-store, must-revalidate" \
  --region "$REGION"

echo "==> Sync hashed assets (immutable, 1y)"
aws s3 sync "$DIST_DIR" "s3://$BUCKET" \
  --exclude "*" --include "assets/*" \
  --cache-control "public, max-age=31536000, immutable" \
  --region "$REGION"

echo "==> Invalidating CloudFront entry assets"
aws cloudfront create-invalidation --distribution-id "$DIST" \
  --paths "/index.html" "/manifest.webmanifest" "/sw.js" "/registerSW.js" \
  --region "$REGION" >/dev/null

URL=$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendUrl`].OutputValue' --output text)

echo "==> Done. Frontend URL: $URL"
