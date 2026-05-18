#!/usr/bin/env bash
# 인프라 배포 wrapper — SSM SecureString 에 박아둔 Basic Auth 해시를
# deploy 시점에 --parameter-overrides 로 전달한다. template/repo 어디에도
# 평문·base64·해시가 박히지 않게 하기 위함.
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

set -euo pipefail

REGION="${REGION:-ap-northeast-2}"
HASH_PARAM="${HASH_PARAM:-/portfolio/cloudfront/basic-auth-hash}"

HASH=$(aws ssm get-parameter \
  --name "$HASH_PARAM" \
  --with-decryption \
  --region "$REGION" \
  --query 'Parameter.Value' \
  --output text)

if [[ -z "$HASH" || "$HASH" == "None" ]]; then
  echo "Error: SSM parameter $HASH_PARAM is empty or missing." >&2
  echo "Run: aws ssm put-parameter --name $HASH_PARAM --type SecureString --value <SHA256_HEX> --region $REGION" >&2
  exit 1
fi

cd "$(dirname "$0")"
exec sam deploy \
  --parameter-overrides "BasicAuthHash=$HASH" \
  "$@"
