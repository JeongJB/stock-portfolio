# Infra (AWS SAM)

`stock-portfolio` 의 인프라를 정의하는 단일 SAM template. FE-5 단계에서 점진적으로 자원을 추가한다.

| 단계 | 추가되는 리소스 | 상태 |
| --- | --- | --- |
| FE-5a | DynamoDB 테이블 + Lambda 실행 IAM Role | 완료 |
| FE-5b | Lambda Function + API Gateway REST + Usage plan/API key | 완료 |
| FE-5c | S3 (frontend assets) + CloudFront + OAC + SPA router | 완료 |
| FE-5e | GitHub Actions 자동 배포 | 예정 |
| FE-5f | CloudWatch LogGroup (보존 14일) + SNS Topic/Subscription + Lambda Errors 알람 | 완료 (이 단계) |

## 사전 요구사항

- AWS CLI v2 (`aws --version`)
- AWS SAM CLI (`sam --version`)
- AWS 자격증명 설정 (`AWS_PROFILE` 또는 SSO 로그인)
- 리전: `ap-northeast-2` (Seoul)
- **JDK** — Gradle toolchain 이 자동 프로비저닝하므로 시스템 JDK 가 낮아도 무방. 첫 실행 시 JDK 25 다운로드로 수 분 소요될 수 있음.

## 1회 수동 작업 — 시크릿 SSM 등록

SAM template 은 시크릿 값 자체를 박지 않고 SSM SecureString 경로만 참조한다. 값은 사용자가 1회 등록한다.

### KIS appkey/appsecret

```bash
aws ssm put-parameter \
  --name /portfolio/kis/app-key \
  --type SecureString \
  --value '<KIS_APP_KEY>' \
  --region ap-northeast-2

aws ssm put-parameter \
  --name /portfolio/kis/app-secret \
  --type SecureString \
  --value '<KIS_APP_SECRET>' \
  --region ap-northeast-2
```

### 프론트엔드 Basic Auth 해시

`infra/deploy.sh` 가 deploy 시점에 SSM 에서 읽어 `BasicAuthHash` 파라미터로 주입한다. 평문 비밀번호는 어디에도 저장되지 않고 SHA-256 해시만 SSM 에 보관.

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
```

값 갱신 시 `--overwrite` 추가. 회전 절차 상세는 [docs/deploy.md](../docs/deploy.md) "프론트엔드 Basic Auth ID/PW 변경" 섹션 참조.

## 기존 `Portfolio` 테이블 충돌 처리

P0 단계에서 콘솔 수동으로 만든 `Portfolio` 테이블이 이미 존재한다면, SAM 으로 같은 이름을 새로 만들 때 `AlreadyExistsException` 이 발생한다. 다음 중 하나 선택:

- **(A) 기존 테이블 삭제 후 재생성** — 데이터가 거의 없거나 재입력 가능한 경우 권장. 콘솔/CLI 로 기존 테이블을 삭제한 뒤 `sam deploy`.
- **(B) 다른 이름으로 배포** — 기존 데이터를 보존해야 할 때. `sam deploy --parameter-overrides TableName=Portfolio-prod` 로 배포. Lambda `PORTFOLIO_TABLE_NAME` 환경변수도 자동으로 같은 값을 받는다.
- **(C) CloudFormation `import`** — 기존 리소스를 stack 에 흡수. 절차가 복잡하므로 데이터 보존이 절대 필수일 때만.

권장: 데이터 거의 없으면 (A), 보존 필요시 (B).

## 빌드 + 배포 절차 (FE-5b)

```bash
# 1. 백엔드 fat-jar (shadowJar) 빌드 (cwd = repo root)
backend/gradlew -p backend shadowJar
# 산출물: backend/build/libs/stock-portfolio-0.0.1-SNAPSHOT-aws.jar
# template.yaml 의 BackendFunction.CodeUri (../backend/build/libs/...-aws.jar) 가 이 경로를 가리킴.
# 버전 변경 시 backend/build.gradle 의 version 과 template.yaml CodeUri 를 함께 수정해야 함.
#
# 왜 bootJar 가 아닌 shadowJar 인가:
#   bootJar 는 의존성을 BOOT-INF/lib/*.jar 로 중첩 패키징한다.
#   Lambda Java 런타임은 그 중첩 레이아웃을 펼쳐서 classpath 에 올리지 못해
#   FunctionInvoker 같은 Spring Cloud Function adapter 클래스를 찾지 못한다.
#   Shadow plugin (com.gradleup.shadow) 으로 의존성을 평탄화한 fat-jar 를 만든다.
#   bootRun 등 로컬 개발 흐름은 영향 없이 그대로 동작한다.

# 2. SAM 검증
cd infra
sam validate --region ap-northeast-2

# 3. 첫 배포 — guided 모드는 직접 sam deploy --guided 로 1회 실행해 samconfig.toml 채운 뒤
#    이후엔 deploy.sh wrapper 사용 (BasicAuthHash 가 SSM 에서 자동 주입돼야 함).
#    guided 단계에서 BasicAuthHash 도 SSM 에서 한 번 꺼내 입력해야 한다.
HASH=$(aws ssm get-parameter --name /portfolio/cloudfront/basic-auth-hash \
  --with-decryption --region ap-northeast-2 --query 'Parameter.Value' --output text)
sam deploy --guided --parameter-overrides "BasicAuthHash=$HASH"

# 4. 이후 배포 — wrapper 가 SSM 에서 자동 주입
./deploy.sh   # 또는 repo 루트에서 infra/deploy.sh
```

> `sam build` 는 SAM 의 Java 빌드 워커플로(Maven/Gradle 자동 실행)를 사용하지 않고 **이미 빌드된 JAR 를 그대로 패키징**하는 방식이다. CodeUri 가 단일 JAR 파일을 가리키면 SAM 은 그 JAR 만 zip 으로 감싸 S3 에 업로드한다. `sam build` 호출 없이 `sam deploy` 로 직행해도 무방.

> `BasicAuthHash` 파라미터는 default 값이 없으므로 직접 `sam deploy` 만 실행하면 changeset 생성이 실패한다. 항상 `infra/deploy.sh` 를 사용하거나, 수동으로 `--parameter-overrides "BasicAuthHash=<해시>"` 를 전달해야 한다.

## 배포 후 검증

### API key 조회

SAM 이 자동 생성한 API key 는 이름이 `<스택>-<논리ID>-<랜덤>` 형태로 잘려 들어가므로 이름으로 grep 하지 않고 **Usage Plan 을 거쳐** 조회한다 (Usage Plan 이름은 우리가 명시 → `stock-portfolio-<stage>-usage-plan`).

```bash
export STAGE=prod
export REGION=ap-northeast-2

export PLAN_ID=$(aws apigateway get-usage-plans --region $REGION \
  --query "items[?name==\`stock-portfolio-${STAGE}-usage-plan\`].id" --output text)

export KEY_ID=$(aws apigateway get-usage-plan-keys \
  --usage-plan-id $PLAN_ID --region $REGION \
  --query 'items[0].id' --output text)

export API_KEY=$(aws apigateway get-api-key \
  --api-key $KEY_ID --include-value --region $REGION \
  --query value --output text)

echo $API_KEY
```

콘솔: API Gateway → Usage Plans → `stock-portfolio-prod-usage-plan` → API Keys 탭 → 연결된 키 `Show`.

### API 호출 (curl)

```bash
export STACK=stock-portfolio
export REGION=ap-northeast-2

export API_URL=$(aws cloudformation describe-stacks \
  --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`BackendApiInvokeUrl`].OutputValue' \
  --output text)

# API_KEY 는 위 "API key 조회" 섹션에서 export 한 값을 그대로 사용

curl -sH "x-api-key: $API_KEY" "$API_URL/api/portfolio" | jq
```

### 콜드 스타트 측정

```bash
# 첫 호출 (콜드)
time curl -sH "x-api-key: $API_KEY" "$API_URL/api/portfolio" -o /dev/null

# 즉시 재호출 (웜)
time curl -sH "x-api-key: $API_KEY" "$API_URL/api/portfolio" -o /dev/null
```

5~10초의 콜드 스타트는 현재 수용 범위. SnapStart 적용은 P2 보류.

### CloudFormation/리소스 상태 확인

1. CloudFormation 콘솔 → `stock-portfolio` 스택이 `UPDATE_COMPLETE` 상태인지.
2. Lambda 콘솔 → `stock-portfolio-prod-api` 함수의 Runtime/Handler/메모리/타임아웃 + **Reserved concurrency = 1** (또는 `LambdaReservedConcurrency` override 값) 확인.
3. API Gateway 콘솔 → REST API `stock-portfolio-prod` 의 stage `prod`, `/api/{proxy+}` ANY 메서드, API key required 켜짐 확인.
4. Usage plan `stock-portfolio-prod-usage-plan` → throttle/quota 값 확인 (디폴트 5 RPS / burst 10 / 일 2000).
5. Budgets 콘솔 → `stock-portfolio-prod-monthly-budget` 가 active 상태이고 첫 ACTUAL 데이터가 채워졌는지 (며칠 걸릴 수 있음). "Receive Billing Alerts" 활성 여부도 Account → Billing preferences 에서 1회 확인.

## FE-5c 프론트엔드 빌드 + 배포

FE-5c 부터 SAM 스택은 `FrontendBucket` (비공개 S3) + `FrontendDistribution` (CloudFront) + `FrontendOAC` + `FrontendSpaRouter` (CloudFront Functions) 를 함께 만든다.
스택 1회 배포 후, 프론트 자산 업데이트는 sam 재배포 없이 **S3 sync + CloudFront invalidate** 만으로 끝낸다.

> CloudFront 분포 최초 생성/큰 변경은 보통 5~15분 소요된다. `sam deploy` 가 멈춰 보여도 정상.

### 1. 백엔드 엔드포인트 + API key 추출

위 "API key 조회" / "API 호출 (curl)" 섹션 참고. 두 값을 환경변수로 노출한 상태에서 시작한다.

```bash
# 위 절차로 export $API_URL, $API_KEY
echo $API_URL    # https://<api-id>.execute-api.ap-northeast-2.amazonaws.com/prod
echo $API_KEY    # <빌드 타임에 박힐 키>
```

### 2. 프론트엔드 빌드 (env 주입)

`VITE_*` 변수는 빌드 타임에 번들로 박힌다 (1인용 수용. 본질적 비밀이 아님 — API key 는 throttle/quota 분리 용도).

```bash
VITE_API_BASE_URL=$API_URL VITE_API_KEY=$API_KEY \
  npm --prefix frontend run build
# 산출물: frontend/dist/
```

### 3. 배포 대상 버킷 + 분포 ID 조회

```bash
export STACK=stock-portfolio
export REGION=ap-northeast-2

export BUCKET=$(aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendBucketName`].OutputValue' --output text)

export DIST=$(aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendDistributionId`].OutputValue' --output text)
```

### 4. S3 sync (assets 는 long-cache, 그 외는 no-cache)

`assets/` 하위 파일은 vite 가 컨텐츠 해시를 붙이므로 immutable 캐시. 그 외(`index.html`, `sw.js`, `manifest.webmanifest`, `registerSW.js`, 아이콘 등)는 매번 갱신 필요.

```bash
# 1) 비-해시 파일: no-cache + 사라진 파일 정리(--delete)
aws s3 sync frontend/dist s3://$BUCKET \
  --delete \
  --exclude "assets/*" \
  --cache-control "no-cache, no-store, must-revalidate" \
  --region $REGION

# 2) 해시 파일: 1년 immutable
aws s3 sync frontend/dist s3://$BUCKET \
  --exclude "*" --include "assets/*" \
  --cache-control "public, max-age=31536000, immutable" \
  --region $REGION
```

### 5. CloudFront 무효화 (해시 안 붙은 진입 자원만)

```bash
aws cloudfront create-invalidation --distribution-id $DIST \
  --paths "/index.html" "/manifest.webmanifest" "/sw.js" "/registerSW.js" \
  --region $REGION
```

> 또는 `infra/deploy-frontend.sh` 가 4·5번을 한 번에 실행한다 (`$BUCKET`, `$DIST`, `$REGION` 환경변수 필요).

### 6. 검증

```bash
aws cloudformation describe-stacks --stack-name $STACK --region $REGION \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendUrl`].OutputValue' --output text
```

위 URL 을 브라우저로 열어 다음을 확인:

- 대시보드 / `/trades` / `/snapshots` 페이지 정상 렌더.
- DevTools Network 에서 `/api/portfolio` 호출이 200, `x-api-key` 헤더 부착 확인.
- DevTools Application → Service Workers 에 SW 등록 + activated 상태 (autoUpdate).
- 주소창에 `/trades` 직접 입력해도 404 안 나는지 확인 (CloudFront Functions SPA 리라이트).
- DevTools Network → 임의 자산 응답 헤더에 `Strict-Transport-Security` / `X-Content-Type-Options` 등 보안 헤더 부착 확인 (`SecurityHeadersPolicy`).

## 롤백

### 백엔드 롤백

이 프로젝트는 Lambda alias 를 사용하지 않는다. 직전 코드로 되돌리려면 이전 JAR 를 다시 빌드해 `sam deploy` 한다 (gradle/git 으로 이전 커밋 체크아웃 후 `shadowJar` → `sam deploy`).

### 프론트엔드 롤백

`FrontendBucket` 은 versioning 활성. 잘못된 배포 직후 즉시 되돌리려면:

```bash
# 1) 직전 버전 ID 확인 (예: index.html)
aws s3api list-object-versions --bucket $BUCKET --prefix index.html --region $REGION

# 2) 직전 버전을 current 로 복사 (객체별 반복)
aws s3api copy-object --bucket $BUCKET --key index.html \
  --copy-source "$BUCKET/index.html?versionId=<이전버전ID>" \
  --region $REGION

# 3) CloudFront invalidate (4번 명령 그대로)
```

다수 객체를 한꺼번에 되돌려야 하면, 직전 빌드 산출물을 로컬에 보관해 두었다가 4·5번 절차를 다시 실행하는 편이 단순하다.

## 스택 삭제

```bash
aws cloudformation delete-stack --stack-name stock-portfolio --region ap-northeast-2
```

> 경고: 스택 삭제 시 `Portfolio` 테이블도 삭제된다 (데이터 손실).
> PITR 이 켜져 있으므로 삭제 직전 시점까지의 백업으로 35일 이내 복구 가능.

## FE-5f 운영 마무리 (로그 보존 + 알람)

이 단계에서 template 에 추가되는 자원:

- `BackendLogGroup` — `/aws/lambda/stock-portfolio-${Stage}-api`, `RetentionInDays=14` (파라미터 `LogRetentionDays` 로 변경 가능)
- `AlarmTopic` — SNS 토픽 `stock-portfolio-${Stage}-alarms`
- `AlarmEmailSubscription` — `surpatience@gmail.com` 이메일 구독 (파라미터 `AlarmEmail` 로 변경 가능)
- `BackendErrorAlarm` — Lambda `Errors > 0` (5분 윈도우, `Sum`, `notBreaching`) 단일 알람. 1인용에 추가 메트릭 알람은 과하므로 1개만 유지

### 1. 첫 배포 전 — 기존 Lambda 자동 생성 LogGroup 충돌 처리

Lambda 가 첫 호출 시 같은 이름의 로그 그룹을 자동 생성한다. 이미 자동 생성된 상태에서 CFN 으로 같은 이름의 LogGroup 을 새로 만들려 하면 `AlreadyExistsException` 으로 실패한다. 다음 중 하나 선택 (둘 다 같은 결과로 수렴):

- **(A) 사전 삭제 후 재배포** — 기존 로그를 보존할 필요가 없으면 가장 단순.
  ```bash
  aws logs delete-log-group \
    --log-group-name /aws/lambda/stock-portfolio-prod-api \
    --region ap-northeast-2
  cd infra && sam deploy
  ```
- **(B) sam deploy 가 충돌하면 같은 명령으로 삭제 후 재시도** — 결과 동일.

### 2. SNS 이메일 구독 confirm (1회)

`sam deploy` 완료 직후 AWS SNS 가 `surpatience@gmail.com` 으로 confirm 메일(제목: "AWS Notification - Subscription Confirmation")을 발송한다. 메일 본문의 "Confirm subscription" 링크를 1회 클릭해야 알람이 실제 메일로 도달한다. 클릭 전까지는 SNS 콘솔에서 구독 상태가 `PendingConfirmation` 으로 보인다.

스팸함도 함께 확인. 메일을 못 받았다면 SNS 콘솔 → Topics → `stock-portfolio-prod-alarms` → Subscriptions → 해당 구독 선택 → "Request confirmation" 으로 재발송.

### 3. 알람 동작 테스트

운영 알람이 정말 메일로 도달하는지 1회 강제 테스트:

```bash
# 알람 상태를 인위적으로 ALARM 으로 바꿔 SNS publish 발생시킴 (메트릭 데이터를 건드리지 않음).
aws cloudwatch set-alarm-state \
  --alarm-name stock-portfolio-prod-backend-errors \
  --state-value ALARM \
  --state-reason "manual test" \
  --region ap-northeast-2

# 약 1~2분 뒤 surpatience@gmail.com 으로 메일 도착 확인.
# 자연스럽게 다음 평가 주기에 OK 로 복귀하며 OKActions 에 의해 복구 메일도 1통 더 옴.
```

또는 Lambda 콘솔에서 강제로 잘못된 페이로드로 invoke → `Errors` 메트릭 발생 → 5분 후 알람.

## 다음 단계

- **FE-5e** — GitHub Actions 자동 배포 파이프라인 (백엔드 `shadowJar` → `sam deploy`, 프론트 build → S3 sync → invalidate).
