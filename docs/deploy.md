# 배포 운영 가이드

> 깊이 있는 설치/명령 설명은 [infra/README.md](../infra/README.md) 를 참조한다. 이 문서는 빠른 참조용 체크리스트다 — 3개월 뒤 본인이 다시 봐도 5분 안에 배포할 수 있도록 핵심만 모은다.

## 한 줄 요약

- **백엔드 코드 변경** → `backend/gradlew -p backend shadowJar` → `cd infra && sam deploy`
- **프론트엔드 변경** → `npm --prefix frontend run build` (env 주입) → `infra/deploy-frontend.sh`
- **인프라 (template.yaml) 변경** → `cd infra && sam deploy`

## 환경

- 리전: `ap-northeast-2` (Seoul)
- 스택명: `stock-portfolio`
- 단일 prod 환경 (1인용 — staging/dev 분리 없음)
- 알람 수신: `surpatience@gmail.com` (SNS)
  - **첫 배포 후 받은편지함에서 "Confirm subscription" 링크 1회 클릭 필수.** 안 누르면 알람이 발생해도 메일이 안 옴.
- 로그 보존: 14일 (`LogRetentionDays` 파라미터로 변경 가능)

## 자주 쓰는 명령 모음

`API_URL` / `API_KEY` / `BUCKET` / `DIST` 환경변수 추출은 [infra/README.md](../infra/README.md) "API key 조회" / "API 호출" / "배포 대상 버킷 + 분포 ID 조회" 섹션을 그대로 사용한다. 아래는 한 화면에 묶은 빠른 참조.

```bash
export STACK=stock-portfolio
export STAGE=prod
export REGION=ap-northeast-2

# API URL
export API_URL=$(aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`BackendApiInvokeUrl`].OutputValue' --output text)

# API key (Usage Plan 경유)
export PLAN_ID=$(aws apigateway get-usage-plans --region $REGION \
  --query "items[?name==\`stock-portfolio-${STAGE}-usage-plan\`].id" --output text)
export KEY_ID=$(aws apigateway get-usage-plan-keys --usage-plan-id $PLAN_ID --region $REGION \
  --query 'items[0].id' --output text)
export API_KEY=$(aws apigateway get-api-key --api-key $KEY_ID --include-value --region $REGION \
  --query value --output text)

# S3 버킷 / CloudFront 분포 ID
export BUCKET=$(aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendBucketName`].OutputValue' --output text)
export DIST=$(aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendDistributionId`].OutputValue' --output text)
```

배포:

```bash
# 백엔드
backend/gradlew -p backend shadowJar
cd infra && sam deploy

# 프론트엔드 (env 주입 필수)
VITE_API_BASE_URL=$API_URL VITE_API_KEY=$API_KEY \
  npm --prefix frontend run build
infra/deploy-frontend.sh
```

API 호출 테스트:

```bash
curl -sH "x-api-key: $API_KEY" "$API_URL/api/portfolio" | jq
```

## 자주 보는 비용 (2026-04 기준 추정, 1인 사용)

- Lambda + API Gateway + DynamoDB + S3 + CloudFront 합계 **월 $0~1**
- 도메인 미사용 (`*.cloudfront.net` 기본 도메인)
- 한투 OpenAPI 호출비 별도 (현재 무료 한도 내)

## 트러블슈팅 (실제 겪은 것 위주)

| 증상 | 원인 | 처방 |
| --- | --- | --- |
| `Stack aws-sam-cli-managed-default ... REVIEW_IN_PROGRESS` | 이전 `sam deploy --guided` 가 changeset 단계에서 끊김 | `aws cloudformation delete-stack --stack-name aws-sam-cli-managed-default --region $REGION` 후 재시도 |
| `ROLLBACK_COMPLETE state and can not be updated` | 첫 stack 생성이 실패해 롤백된 상태 | 같은 `delete-stack` 후 재시도. 원인은 `aws cloudformation describe-stack-events` 로 확인 |
| Lambda `Class not found: FunctionInvoker` | bootJar 의 `BOOT-INF/lib/*.jar` 중첩 구조를 Lambda 가 못 펼침 | `shadowJar` (`com.gradleup.shadow`) 사용 — 이미 `backend/build.gradle` 에 적용됨 |
| Lambda `No auto configuration classes found in ... AutoConfiguration.imports` | shadow plugin 9.x 의 transformer DSL 한계로 spring imports 가 덮어써짐 | `build.gradle` 의 `doLast` 가 `META-INF/spring/*.imports` 를 라인 병합 — 이미 적용됨 |
| DynamoDB `Query condition missed key schema element: PK` | 코드는 소문자 `pk/sk`, template KeySchema 가 대문자였던 케이스 불일치 | template KeySchema 가 소문자 `pk/sk/gsi1pk/gsi1sk` 인지 확인 — 이미 적용됨 |
| 브라우저 CORS error | `Method: ANY` 가 OPTIONS preflight 까지 잡아 ApiKeyRequired 적용 → 403 | OPTIONS 만 별도 라우트 + `ApiKeyRequired: false` 로 분리 — 이미 적용됨 |
| Lambda 첫 호출 5~10초 느림 | Spring Boot 4 + SCF 콜드 스타트 | 1인용 수용. SnapStart 적용은 P2 보류 |
| `aws-sam-cli-managed-default` 첫 생성 실패 | S3 또는 IAM 권한 부족 | IAM 사용자에 권한 추가 후 재시도 |
| `LogGroup ... already exists` | Lambda 가 첫 호출 시 자동 생성한 로그 그룹과 CFN 정의 충돌 | `aws logs delete-log-group --log-group-name /aws/lambda/stock-portfolio-prod-api --region $REGION` 후 `sam deploy` 재시도 |
| 알람 메일이 오지 않음 | SNS email 구독이 PendingConfirmation 상태 | `surpatience@gmail.com` 받은편지함에서 "Confirm subscription" 링크 클릭 (스팸함도 확인) |

## 롤백

- **백엔드**: 직전 git commit 체크아웃 → `backend/gradlew -p backend shadowJar` → `cd infra && sam deploy`
- **프론트엔드**: S3 versioning 으로 객체 단위 복원 + CloudFront invalidate. 절차는 [infra/README.md](../infra/README.md) "프론트엔드 롤백" 섹션 참고

## 해서는 안 되는 것

- `aws cloudformation delete-stack --stack-name stock-portfolio` — DynamoDB 데이터까지 삭제됨 (PITR 있어도 35일 한정 복구). 정말 필요할 때만.
- IAM 사용자 액세스 키를 `samconfig.toml` 등 커밋되는 파일에 박는 것 — 항상 환경변수 또는 SSO 로 주입.
- KIS appkey/appsecret 을 코드/template/Outputs 에 박는 것 — 항상 SSM SecureString.
