# 배포 운영 가이드

> 깊이 있는 설치/명령 설명은 [infra/README.md](../infra/README.md) 를 참조한다. 이 문서는 빠른 참조용 체크리스트다 — 3개월 뒤 본인이 다시 봐도 5분 안에 배포할 수 있도록 핵심만 모은다.

## 한 줄 요약

- **백엔드 코드 변경** → `backend/gradlew -p backend shadowJar` → `infra/deploy.sh`
- **프론트엔드 변경** → `npm --prefix frontend run build` (env 주입) → `infra/deploy-frontend.sh`
- **인프라 (template.yaml) 변경** → `infra/deploy.sh`

> `infra/deploy.sh` 는 SSM SecureString `/portfolio/cloudfront/basic-auth-hash` 에 보관된 Basic Auth 해시를 deploy 시점에 `--parameter-overrides` 로 주입하는 wrapper 다 (template/repo 에 해시가 박히지 않도록). 직접 `sam deploy` 를 호출하면 `BasicAuthHash` 파라미터가 비어 배포 실패 — 항상 wrapper 사용.

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
infra/deploy.sh

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
- **AWS Budgets 월 $5 한도** + ACTUAL 80% / FORECASTED 100% 이메일 알림 (`AlarmEmail` 파라미터로 동일 주소 사용). 평소 비용의 5배에 도달하면 메일이 옴 → 비용 폭주 조기 감지.
  - **첫 배포 후 1회**: AWS Billing console (Account → Billing preferences) 에서 "Receive Billing Alerts" 가 활성인지 확인. 비활성이면 ACTUAL 메트릭이 publish 되지 않아 알림이 동작하지 않음.

## 보안·비용 가드레일 layer (WAF 미사용)

비용 0 으로 brute force·L7 flood·비용 폭주를 막는 4-layer:

| Layer | 메커니즘 | 디폴트 |
|---|---|---|
| L1 GeoRestriction | CloudFront 가 KR 외 IP 차단 | KR-only |
| L2 Basic Auth | CloudFront Function 의 sha256 게이트 | SSM SecureString 보관 |
| L3 API Gateway throttle/quota | Usage plan + API key | 5 RPS / burst 10 / 일 2000 |
| L4 Lambda Reserved Concurrency | 동시 실행 상한으로 비용 폭주 차단 | **1** (한도 증액 1000 적용 완료) |

`ApiThrottleRateLimit / ApiThrottleBurstLimit / ApiQuotaPerDay / LambdaReservedConcurrency / MonthlyBudgetUsd` 파라미터 override 로 즉시 조정 가능.

### Lambda concurrent executions quota 메모

현재 AWS 계정 한도는 1000 (Service Quotas: Lambda `L-B99A9384`). 신규 AWS 계정은 기본 10 으로 묶여 있어 reserved 1 도 거부될 수 있다 (unreserved 9 < AWS hard floor 10). 새 계정/리전에서 같은 함정을 만나면:

```bash
# 한도 확인
aws service-quotas get-service-quota \
  --service-code lambda --quota-code L-B99A9384 \
  --region ap-northeast-2 --query 'Quota.Value'

# 한도 증액 요청 (보통 자동 승인, 길어도 1~2 영업일)
aws service-quotas request-service-quota-increase \
  --service-code lambda --quota-code L-B99A9384 \
  --desired-value 1000 --region ap-northeast-2

# 승인 전 임시 우회 — reserved 안 잡고 unreserved pool 사용
sam deploy --parameter-overrides LambdaReservedConcurrency=0 \
  --no-confirm-changeset --region ap-northeast-2
```

## Lambda SnapStart + cold start 최적화

콜드 첫 호출 wall clock 6.2s → ~2-2.5s 단축. SnapStart 기본 (`SnapStart: ApplyOn: PublishedVersions` + `AutoPublishAlias: live`) + 4단계 추가 최적화.

### 동작

- 매 배포에서 SAM 이 새 버전 발행 → snapshot 생성 → `live` alias 갱신 → API Gateway integration / Lambda permission 까지 alias ARN 으로 박음.
- 함수 코드/설정 변경이 없는 배포는 새 버전 발행 자체를 건너뛰어 snapshot 재생성 비용 없음.
- **첫 SnapStart 활성화 배포만** snapshot 빌드로 평소보다 5~15분 더 걸림. 이후 배포는 정상.
- snapshot 은 14일 idle 시 자동 폐기되며 다음 호출에서 자동 재생성 — 1인용 빈도에선 무관.

### 4단계 최적화

| 단계 | 내용 |
|---|---|
| KIS RestClient connection pool | `KisMarketDataConfig.kisRestClient`: `SimpleClientHttpRequestFactory` (매 호출 새 TCP) → `JdkClientHttpRequestFactory` + JDK `HttpClient` (HTTP/2 + keep-alive). |
| `SnapStartWarmup` init priming | `@PostConstruct` 에서 `dynamoDbClient.describeTable` + `marketDataPort.getUsdKrwRate()` 호출 → DDB SDK lazy init, KIS access token (DDB), SSM credentials, fxCache 모두 채워 snapshot 에 박힘. |
| DDB persistent `FxRateStore` | `META#fx / USD_KRW` 항목. `getUsdKrwRate()` 3-layer (in-memory → DDB → KIS/fallback). SnapStart 의 init phase 2회 race (두 번째 init 이 KIS EGW00133 으로 거부) 해소. |
| `FX_TTL` 1h → 6h | snapshot 박힌 fxCache 의 유효 윈도우 확장. 환율 시간당 변동 미미 → 1인용에 충분. |

### 상태 확인

```bash
aws lambda get-function \
  --function-name stock-portfolio-prod-api \
  --qualifier live \
  --region $REGION \
  --query 'Configuration.SnapStart'
# ApplyOn=PublishedVersions, OptimizationStatus=On 이어야 정상.

# warmup 동작 확인 (init phase 로그)
aws logs filter-log-events --log-group-name /aws/lambda/stock-portfolio-prod-api \
  --filter-pattern "SnapStartWarmup" --region $REGION \
  --max-items 20 --query 'events[].message' --output text
# "SnapStartWarmup: getUsdKrwRate OK rate=..." 가 보여야 정상.
# 두 번째 INIT 의 priming 이 DDB hit 으로 빠르게(<100ms) 끝나야 함.
```

### 임시 비활성화 / 롤백

- 임시로 SnapStart 끄고 싶다면 template `SnapStart.ApplyOn` 을 `None` 으로 바꿔 deploy. AutoPublishAlias 는 그대로 둬도 무해.
- **롤백**: git revert + `infra/deploy.sh` 만으로 충분. alias 가 자동으로 직전 안정 버전을 가리킨다.

### IAM 1회 작업

`SnapStartWarmup` 이 priming 으로 `dynamodb:DescribeTable` 을 호출하므로 SAM template `LambdaExecutionRole` 의 `PortfolioTableAccess` 정책에 해당 액션이 박혀 있어야 함. 누락 시 priming 실패 + cold first invoke 가 다시 느려짐.

## 트러블슈팅 (실제 겪은 것 위주)

| 증상 | 원인 | 처방 |
| --- | --- | --- |
| `Stack aws-sam-cli-managed-default ... REVIEW_IN_PROGRESS` | 이전 `sam deploy --guided` 가 changeset 단계에서 끊김 | `aws cloudformation delete-stack --stack-name aws-sam-cli-managed-default --region $REGION` 후 재시도 |
| `ROLLBACK_COMPLETE state and can not be updated` | 첫 stack 생성이 실패해 롤백된 상태 | 같은 `delete-stack` 후 재시도. 원인은 `aws cloudformation describe-stack-events` 로 확인 |
| Lambda `Class not found: FunctionInvoker` | bootJar 의 `BOOT-INF/lib/*.jar` 중첩 구조를 Lambda 가 못 펼침 | `shadowJar` (`com.gradleup.shadow`) 사용 — 이미 `backend/build.gradle` 에 적용됨 |
| Lambda `No auto configuration classes found in ... AutoConfiguration.imports` | shadow plugin 9.x 의 transformer DSL 한계로 spring imports 가 덮어써짐 | `build.gradle` 의 `doLast` 가 `META-INF/spring/*.imports` 를 라인 병합 — 이미 적용됨 |
| DynamoDB `Query condition missed key schema element: PK` | 코드는 소문자 `pk/sk`, template KeySchema 가 대문자였던 케이스 불일치 | template KeySchema 가 소문자 `pk/sk/gsi1pk/gsi1sk` 인지 확인 — 이미 적용됨 |
| 브라우저 CORS error | `Method: ANY` 가 OPTIONS preflight 까지 잡아 ApiKeyRequired 적용 → 403 | OPTIONS 만 별도 라우트 + `ApiKeyRequired: false` 로 분리 — 이미 적용됨 |
| Lambda 첫 호출이 여전히 5~10초 느림 | SnapStart 가 활성화돼 있어도 직전 배포에서 새 버전이 발행되지 않았거나 (코드/설정 무변경) snapshot 재사용이 아직 안 된 케이스 | 평소: 1~2s 대로 단축됨. 첫 SnapStart 활성 배포 직후엔 snapshot 빌드 5~15분 진행 중일 수 있다. `aws lambda get-function --function-name stock-portfolio-prod-api --qualifier live` 로 `SnapStart.OptimizationStatus=On` 인지 확인 |
| `SnapStartWarmup: getUsdKrwRate 실패 (무시): java.lang.IllegalStateException: FX 폴백 응답...` (init 로그) + cold first invoke 의 fxRate 가 1초+ | SnapStart deploy 시 init phase 2회 실행 → 두 번째 init 의 KIS access token 발급이 EGW00133 (1분당 1회) 으로 거부 + frankfurter fallback 도 실패 → snapshot 의 fxCache 빈 상태 | `FxRateStore` (DDB persistent fxCache) 가 적용됐는지 확인. 두 번째 init 이 DDB hit 으로 회피 가능. `META#fx / USD_KRW` 항목 존재 확인: `aws dynamodb get-item --table-name Portfolio --key '{"pk":{"S":"META#fx"},"sk":{"S":"USD_KRW"}}'` |
| cold first invoke 의 `load=N초` 가 큼 (priming 적용 후에도) | `dynamodb:DescribeTable` IAM 권한 누락 → `SnapStartWarmup` 의 DDB priming 실패 → SDK lazy init 이 view() 의 첫 호출에서 발생 | `LambdaExecutionRole.PortfolioTableAccess` 에 `dynamodb:DescribeTable` 추가 확인. CloudWatch Logs 에서 `SnapStartWarmup: DDB describeTable 실패` 로그가 있으면 권한 문제 |
| `aws-sam-cli-managed-default` 첫 생성 실패 | S3 또는 IAM 권한 부족 | IAM 사용자에 권한 추가 후 재시도 |
| `LogGroup ... already exists` | Lambda 가 첫 호출 시 자동 생성한 로그 그룹과 CFN 정의 충돌 | `aws logs delete-log-group --log-group-name /aws/lambda/stock-portfolio-prod-api --region $REGION` 후 `sam deploy` 재시도 |
| 알람 메일이 오지 않음 | SNS email 구독이 PendingConfirmation 상태 | `surpatience@gmail.com` 받은편지함에서 "Confirm subscription" 링크 클릭 (스팸함도 확인) |
| 정상 사용 중 갑자기 `429 Too Many Requests` | Usage plan throttle 5 RPS / burst 10 한도 초과 (대개 두 탭 동시 새로고침 또는 단기간 query 폭) | 일시적 — 잠시 후 자동 회복. 자주 발생하면 `ApiThrottleBurstLimit` 만 15~20 으로 올려 redeploy |
| `Specified ReservedConcurrentExecutions ... below its minimum value of [10]` | 신규 AWS 계정 Lambda quota 10 인 상태에서 reserved 1 적용 시 unreserved 9 < AWS hard floor 10 → 거부 | `LambdaReservedConcurrency=0` (디폴트) 로 일단 우회. Service Quotas 에서 Lambda concurrent executions 1000 증액 신청 후 1 로 활성화 |
| `UPDATE_ROLLBACK_FAILED` 상태에 갇힘 | 직전 deploy 실패 + 자동 rollback 도 IAM 권한 부족 등으로 실패 | `aws cloudformation continue-update-rollback --stack-name stock-portfolio` (권한 보강 후). 그래도 막히면 `--resources-to-skip <LogicalId>` 로 마지막 카드 |
| Budgets 알림이 안 옴 | AWS Billing console 의 "Receive Billing Alerts" 비활성 또는 EMAIL subscription 미확인 | Billing console 에서 활성화 + `AlarmEmail` 받은편지함에서 첫 알림 확인 |

## 프론트엔드 Basic Auth ID/PW 변경

프론트엔드 진입은 CloudFront Function (`FrontendSpaRouter`) 의 viewer-request 단계에서 Basic Auth 게이트를 통과해야 한다. **자격증명 자체는 어디에도 박지 않는다** — SHA-256 해시만 SSM SecureString `/portfolio/cloudfront/basic-auth-hash` 에 두고, `infra/deploy.sh` 가 deploy 시점에 `BasicAuthHash` 파라미터로 주입한다. 함수는 viewer-request 마다 들어온 `Authorization` 헤더 전체를 sha256 해시 후 비교한다.

### 최초 1회 설정

```bash
USER="june"
PASS="<INITIAL_PASSWORD>"
TOKEN=$(printf '%s' "$USER:$PASS" | base64)
HASH=$(printf '%s' "Basic $TOKEN" | shasum -a 256 | awk '{print $1}')

aws ssm put-parameter \
  --name /portfolio/cloudfront/basic-auth-hash \
  --type SecureString \
  --value "$HASH" \
  --region ap-northeast-2

infra/deploy.sh
```

평문 비밀번호는 1Password 등 비밀번호 매니저에 별도 보관. AWS 어디에도 평문은 없고, template/repo 에도 박혀 있지 않다.

### 비밀번호 회전

```bash
USER="june"
PASS="<NEW_PASSWORD>"
TOKEN=$(printf '%s' "$USER:$PASS" | base64)
HASH=$(printf '%s' "Basic $TOKEN" | shasum -a 256 | awk '{print $1}')

aws ssm put-parameter \
  --name /portfolio/cloudfront/basic-auth-hash \
  --type SecureString \
  --value "$HASH" \
  --overwrite \
  --region ap-northeast-2

infra/deploy.sh
```

template/코드 변경 0줄. SSM 갱신 + deploy 한 번이면 끝.

### 전파/캐시 주의

- CloudFront Function 갱신은 보통 1~5분 안에 모든 edge 에 반영됨.
- 브라우저는 도메인별 자격증명을 **세션** 동안 캐시하므로 변경 직후 다이얼로그가 안 뜰 수 있다. 시크릿 창 또는 `Cmd+Shift+R` 강제 새로고침으로 검증.
- macOS Keychain / 1Password 등 옛 자격증명이 자동 입력될 수 있어 새 PW 입력 시 함께 갱신 권장.

### 검증 (브라우저 캐시 영향 없는 curl)

```bash
FRONTEND_URL=$(aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendUrl`].OutputValue' --output text)

curl -I "$FRONTEND_URL/"                       # 401 + WWW-Authenticate: Basic ... 정상
curl -I -u "$USER:$PASS" "$FRONTEND_URL/"      # 200 정상
curl -I -u "wrong:wrong" "$FRONTEND_URL/"      # 401 정상
```

> 비밀번호 분실 시 SSM 에 새 해시를 넣고 deploy 하면 곧 새 PW 설정. 옛 PW 는 해시만 남아 brute force 외엔 복구 불가 (16자 랜덤이면 사실상 불가능).

## 롤백

- **백엔드**: 직전 git commit 체크아웃 → `backend/gradlew -p backend shadowJar` → `infra/deploy.sh`
- **프론트엔드**: S3 versioning 으로 객체 단위 복원 + CloudFront invalidate. 절차는 [infra/README.md](../infra/README.md) "프론트엔드 롤백" 섹션 참고

## 해서는 안 되는 것

- `aws cloudformation delete-stack --stack-name stock-portfolio` — DynamoDB 데이터까지 삭제됨 (PITR 있어도 35일 한정 복구). 정말 필요할 때만.
- IAM 사용자 액세스 키를 `samconfig.toml` 등 커밋되는 파일에 박는 것 — 항상 환경변수 또는 SSO 로 주입.
- KIS appkey/appsecret 을 코드/template/Outputs 에 박는 것 — 항상 SSM SecureString.
