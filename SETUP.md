# Setup Guide

이 앱을 처음부터 끝까지 배포해 사용 가능한 상태로 만드는 **순차 가이드**. 위에서부터 차례로 따라 하면 약 30~60분 안에 완료됩니다.

각 단계의 상세·트러블슈팅은 끝의 [참고 문서](#참고-문서) 링크 참조.

---

## 0. 사전 도구 설치

| 도구 | 설치 확인 | 용도 |
| --- | --- | --- |
| AWS CLI v2 | `aws --version` | 모든 AWS 작업 |
| AWS SAM CLI | `sam --version` | 인프라 배포 |
| GitHub CLI (`gh`) | `gh --version` | GitHub Secret 등록 |
| git | `git --version` | 리포 클론 |
| Node.js 20+ | `node --version` | 프론트 빌드 (운영 시 GitHub Actions 가 처리, 로컬 빌드 시 필요) |
| Docker | `docker info` | 백엔드 통합 테스트 (testcontainers, 로컬 테스트 시만 필요) |

> JDK 는 Gradle toolchain 이 자동 다운로드하므로 시스템 JDK 가 낮아도 OK.

---

## 1. AWS 계정 준비

### 1-1. 자격증명 설정

```bash
aws configure                                 # 또는 SSO: aws sso login
aws sts get-caller-identity                   # 본인 계정 ID 확인
```

이후 단계의 `<ACCOUNT_ID>` 는 위 명령 결과의 `Account` 값을 사용합니다.

### 1-2. 기본 리전 고정

이 앱은 **`ap-northeast-2`** (Seoul) 단일 리전. 모든 명령에 `--region ap-northeast-2` 가 들어갑니다. 편의를 위해:

```bash
export AWS_DEFAULT_REGION=ap-northeast-2
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
```

---

## 2. 시크릿 SSM 등록 (1회)

세 개의 SSM SecureString 을 등록합니다. 시크릿은 코드·template 에 박지 않습니다.

### 2-1. KIS 한국투자증권 OpenAPI 키

https://apiportal.koreainvestment.com 에서 OpenAPI 신청 후 받은 `appkey` / `appsecret`:

```bash
aws ssm put-parameter --name /portfolio/kis/app-key \
  --type SecureString --value '<KIS_APP_KEY>'

aws ssm put-parameter --name /portfolio/kis/app-secret \
  --type SecureString --value '<KIS_APP_SECRET>'
```

### 2-2. CloudFront Basic Auth 해시

프론트엔드 진입을 막는 Basic Auth 의 SHA-256 해시. **평문 비밀번호는 1Password 등에 별도 보관**.

```bash
USER="<원하는 사용자명, 예: june>"
PASS="<원하는 비밀번호 (16자 이상 랜덤 권장)>"

TOKEN=$(printf '%s' "$USER:$PASS" | base64)
HASH=$(printf '%s' "Basic $TOKEN" | shasum -a 256 | awk '{print $1}')

aws ssm put-parameter --name /portfolio/cloudfront/basic-auth-hash \
  --type SecureString --value "$HASH"
```

> 비밀번호 회전: 같은 명령에 `--overwrite` 추가 후 `infra/deploy.sh` 재실행.

---

## 3. GitHub Actions OIDC 자동 배포 설정 (1회)

장기 액세스 키 없이 GitHub Actions → AWS 로 자격증명을 주입하는 OIDC 셋업.

### 3-1. OIDC provider 등록 (계정 첫 사용 시만)

```bash
# 이미 등록돼 있는지 확인
aws iam list-open-id-connect-providers \
  | grep token.actions.githubusercontent.com

# 없으면 등록
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

### 3-2. IAM Role 생성

```bash
# 리포 루트에서 실행 (infra/iam/*.json 에 ACCOUNT_ID 치환)
sed "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" infra/iam/trust-policy.json > /tmp/trust.json
sed "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" infra/iam/gha-deploy-policy.json > /tmp/policy.json

aws iam create-role \
  --role-name stock-portfolio-gha-deploy \
  --assume-role-policy-document file:///tmp/trust.json

aws iam put-role-policy \
  --role-name stock-portfolio-gha-deploy \
  --policy-name stock-portfolio-gha-deploy \
  --policy-document file:///tmp/policy.json
```

### 3-3. GitHub Secret 등록

```bash
gh secret set AWS_ROLE_ARN \
  --repo JeongJB/stock-portfolio \
  --body "arn:aws:iam::$ACCOUNT_ID:role/stock-portfolio-gha-deploy"
```

> 다른 사용자 본인의 fork 라면 `--repo <owner>/<repo>` 를 본인 리포 경로로 수정. 그리고 `infra/iam/trust-policy.json` 의 `repo:JeongJB/stock-portfolio:ref:refs/heads/master` sub claim 도 본인 리포로 변경 후 IAM Role trust policy 갱신 필요.

---

## 4. 첫 인프라 배포 (1회 — guided 모드)

이후 배포는 GitHub Actions 가 자동으로 처리하지만, **첫 배포만큼은 로컬에서 `sam deploy --guided`** 로 `samconfig.toml` 을 채워야 합니다.

### 4-1. 백엔드 jar 빌드

```bash
backend/gradlew -p backend shadowJar
# 산출물: backend/build/libs/stock-portfolio-0.0.1-SNAPSHOT-aws.jar
```

### 4-2. SAM guided deploy

```bash
HASH=$(aws ssm get-parameter --name /portfolio/cloudfront/basic-auth-hash \
  --with-decryption --query 'Parameter.Value' --output text)

cd infra
sam deploy --guided --parameter-overrides "BasicAuthHash=$HASH"
```

guided 프롬프트:
- **Stack Name**: `stock-portfolio`
- **Region**: `ap-northeast-2`
- **Confirm changes before deploy**: `Y`
- **Allow SAM CLI IAM role creation**: `Y`
- **Disable rollback**: `N`
- **Save arguments to configuration file**: `Y`
- 나머지는 default

CloudFront 분포 첫 생성에 5~15분 소요. `sam deploy` 가 멈춰 보여도 정상.

### 4-3. (필요 시) 충돌 처리

#### 기존 `Portfolio` DynamoDB 테이블이 있는 경우

P0 단계에서 콘솔로 만든 적이 있다면:

```bash
# 데이터가 없거나 재입력 가능하면 삭제
aws dynamodb delete-table --table-name Portfolio
# 잠시 후 sam deploy 재시도
```

#### 기존 Lambda LogGroup 충돌

```bash
aws logs delete-log-group --log-group-name /aws/lambda/stock-portfolio-prod-api
# 그 후 sam deploy 재시도
```

---

## 5. 운영 알람 활성화 (1회)

### 5-1. SNS 구독 메일 confirm

`sam deploy` 직후 `surpatience@gmail.com` (template 의 `AlarmEmail` 파라미터 — 본인 메일로 변경 시 그쪽으로) 으로 SNS 가 confirm 메일을 발송합니다.

- 메일 제목: **"AWS Notification - Subscription Confirmation"**
- 본문의 **"Confirm subscription"** 링크 1회 클릭
- 스팸함도 확인

> 못 받았다면 SNS 콘솔 → Topics → `stock-portfolio-prod-alarms` → Subscriptions → 해당 구독 선택 → "Request confirmation" 으로 재발송.

### 5-2. (선택) 알람 동작 테스트

알람 → 메일까지 흐름이 정말 동작하는지 1회 테스트:

```bash
aws cloudwatch set-alarm-state \
  --alarm-name stock-portfolio-prod-backend-errors \
  --state-value ALARM \
  --state-reason "manual test"
# 1~2분 뒤 메일 도착 확인. 자연 OK 복귀 시 복구 메일도 1통.
```

---

## 6. 첫 프론트엔드 배포

`sam deploy` 는 인프라(빈 S3 버킷 + CloudFront)만 만듭니다. 정적 자산은 별도 배포:

### 6-1. API URL + API key 추출

```bash
export STACK=stock-portfolio
export STAGE=prod

export API_URL=$(aws cloudformation describe-stacks --stack-name $STACK \
  --query 'Stacks[0].Outputs[?OutputKey==`BackendApiInvokeUrl`].OutputValue' --output text)

export PLAN_ID=$(aws apigateway get-usage-plans \
  --query "items[?name==\`stock-portfolio-${STAGE}-usage-plan\`].id" --output text)
export KEY_ID=$(aws apigateway get-usage-plan-keys --usage-plan-id $PLAN_ID \
  --query 'items[0].id' --output text)
export API_KEY=$(aws apigateway get-api-key --api-key $KEY_ID --include-value \
  --query value --output text)
```

### 6-2. 빌드 + 배포

```bash
VITE_API_BASE_URL=$API_URL VITE_API_KEY=$API_KEY \
  npm --prefix frontend run build

infra/deploy-frontend.sh
```

`deploy-frontend.sh` 가 S3 sync (assets long-cache, 그 외 no-cache) + CloudFront invalidation 까지 처리.

---

## 7. 사용 시작

### 7-1. 프론트엔드 URL 확인

```bash
aws cloudformation describe-stacks --stack-name stock-portfolio \
  --query 'Stacks[0].Outputs[?OutputKey==`FrontendUrl`].OutputValue' --output text
```

브라우저에서 위 URL 접속 → Basic Auth 다이얼로그 → 2-2 에서 정한 USER/PASS 입력.

### 7-2. PWA 설치 (선택)

데스크톱: 주소창 우측 설치 아이콘 또는 헤더의 "앱 설치" 버튼.
모바일: Chrome/Samsung Internet 메뉴 → "앱 설치" 또는 "홈 화면에 추가".

### 7-3. 첫 거래 입력

1. 헤더 → "거래 입력"
2. **DEPOSIT** 탭에서 시드 USD 금액 입력 → 제출
3. **BUY** 탭에서 ticker (예: `AAPL`) + 수량 + 단가 입력 → 제출
   - 첫 매수 시 백엔드가 NAS → NYS → AMS 순차로 거래소를 자동 탐색해 META 에 박제 (수~수십 초 소요 가능)
4. 헤더 → "대시보드" 에서 합계 카드·파이 차트·포지션 표 확인
5. 헤더 → "스냅샷" → "지금 박제" 로 첫 시계열 점 생성

---

## 이후 운영

자동 배포 흐름이 활성화된 상태:
- `master` push 시 GitHub Actions 가 자동으로 빌드 + 배포
- `backend/**` 변경 → backend-deploy 워크플로
- `frontend/**` 변경 → frontend-deploy 워크플로

수동 배포가 필요한 경우는 [docs/deploy.md](docs/deploy.md) 참조.

---

## 참고 문서

- **[README.md](README.md)** — 프로젝트 개요, 기술 스택, 데이터 모델
- **[CLAUDE.md](CLAUDE.md)** — 진행 로드맵, 완료 항목, 핵심 결정사항
- **[docs/deploy.md](docs/deploy.md)** — 배포 운영 빠른 참조, 트러블슈팅, Basic Auth 회전
- **[infra/README.md](infra/README.md)** — SAM 인프라 상세, 빌드/배포 절차, 롤백
- **[infra/iam/README.md](infra/iam/README.md)** — GitHub Actions OIDC IAM Role 상세

---

## 체크리스트 (전체 완료 확인)

- [ ] AWS 자격증명 + 리전 설정 (`aws sts get-caller-identity` 성공)
- [ ] KIS appkey/appsecret SSM 등록
- [ ] Basic Auth 해시 SSM 등록 (평문 PW 별도 보관)
- [ ] GitHub OIDC provider 등록
- [ ] IAM Role `stock-portfolio-gha-deploy` 생성
- [ ] GitHub Secret `AWS_ROLE_ARN` 등록
- [ ] 첫 `sam deploy --guided` 성공
- [ ] SNS 구독 메일 confirm 클릭
- [ ] (선택) 알람 동작 테스트
- [ ] 프론트엔드 빌드 + `deploy-frontend.sh` 실행
- [ ] CloudFront URL 접속 + Basic Auth 통과
- [ ] 첫 DEPOSIT + BUY 입력
- [ ] (선택) PWA 설치
