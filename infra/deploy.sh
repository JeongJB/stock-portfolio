#!/usr/bin/env bash
# 인프라 배포 wrapper — SSM SecureString 에 박아둔 두 시크릿
#   - Basic Auth 해시 (CloudFront viewer-request 함수가 검증)
#   - X-Origin-Verify (CloudFront → API Gateway origin custom header, Lambda 가 검증)
# 을 deploy 시점에 --parameter-overrides 로 전달한다. template/repo 어디에도
# 평문/해시가 박히지 않게 하기 위함.
#
# 사용법:
#   infra/deploy.sh                 # 디폴트 deploy
#   infra/deploy.sh --no-confirm-changeset
#   추가 인자는 그대로 sam deploy 에 전달된다.
#
# 사전 조건:
#   aws ssm put-parameter \
#     --name /portfolio/cloudfront/basic-auth-hash \
#     --type SecureString \
#     --value <SHA256_HEX> \
#     --region ap-northeast-2
#   aws ssm put-parameter \
#     --name /portfolio/api-gateway/origin-verify \
#     --type SecureString \
#     --value "$(openssl rand -hex 32)" \
#     --region ap-northeast-2

set -euo pipefail

REGION="${REGION:-ap-northeast-2}"
HASH_PARAM="${HASH_PARAM:-/portfolio/cloudfront/basic-auth-hash}"
ORIGIN_VERIFY_PARAM="${ORIGIN_VERIFY_PARAM:-/portfolio/api-gateway/origin-verify}"

fetch_secure() {
  local name="$1"
  aws ssm get-parameter \
    --name "$name" \
    --with-decryption \
    --region "$REGION" \
    --query 'Parameter.Value' \
    --output text
}

HASH=$(fetch_secure "$HASH_PARAM")
if [[ -z "$HASH" || "$HASH" == "None" ]]; then
  echo "Error: SSM parameter $HASH_PARAM is empty or missing." >&2
  echo "Run: aws ssm put-parameter --name $HASH_PARAM --type SecureString --value <SHA256_HEX> --region $REGION" >&2
  exit 1
fi

ORIGIN_VERIFY=$(fetch_secure "$ORIGIN_VERIFY_PARAM")
if [[ -z "$ORIGIN_VERIFY" || "$ORIGIN_VERIFY" == "None" ]]; then
  echo "Error: SSM parameter $ORIGIN_VERIFY_PARAM is empty or missing." >&2
  echo "Run: aws ssm put-parameter --name $ORIGIN_VERIFY_PARAM --type SecureString --value \"\$(openssl rand -hex 32)\" --region $REGION" >&2
  exit 1
fi

cd "$(dirname "$0")"
exec sam deploy \
  --parameter-overrides "BasicAuthHash=$HASH" "OriginVerifyHash=$ORIGIN_VERIFY" \
  "$@"
