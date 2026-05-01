# Stock Portfolio

개인용 **미국 주식 포트폴리오 관리** 애플리케이션. 1인 사용자 전제로 비용 최소화에 초점을 맞춘 AWS 서버리스 PWA.

## 주요 기능

- **대시보드** — 평가액 / 원금 / 평가손익 / 연환산 수익률(IRR) / 단순 누적 수익률 5장 카드, 보유 비중 파이 차트(legend 포함), 포지션 표
- **거래 입력** — `BUY` / `SELL` / `DIVIDEND` / `DEPOSIT` / `WITHDRAW` 5종 거래. 보유 종목 dropdown(매도·배당) + 신규/추가 매수 모드 토글로 오타·미보유 종목 매도 방지
- **거래 내역** — 종류별 색상 배지 + 비고(memo) 표시 + 행별 삭제 (replay 기반 422 검증으로 잔고/포지션 깨짐 차단)
- **스냅샷 추이** — 사용자 지정 시점 박제 → 1달 / 3달 / 6달 / 1년 / 5년 / 사용자 지정 기간 라인 차트
- **이미지 내보내기** — 대시보드 PNG 캡처 (검정 배경)
- **주간장 자동 매핑** — KIS 정규장 / 주간장(BAY/BAQ/BAA) 자동 전환 + fallback
- **거래소 자동 탐색** — ticker 만 입력해도 NAS → NYS → AMS 순차 탐색 후 META 박제, 카운터 ≥ 3 시 view() 안에서 자동 재탐색
- **모바일 PWA** — 설치 가능, 모바일 레이아웃 (가로 스크롤 표·44px 터치 영역)

## 저장소 구조

```
.
├── backend/              # Spring Boot 4.0.6 + Java 25 + AWS Lambda + DynamoDB
├── frontend/             # React 19 + Vite + TypeScript + PWA + Tailwind v4
├── infra/                # AWS SAM (CloudFormation), 배포 wrapper, IAM 정책 JSON
│   ├── template.yaml     # 단일 IaC 스택 — DDB / Lambda / API Gateway / S3+CloudFront / SNS 알람
│   ├── deploy.sh         # 백엔드 + 프론트 인프라 deploy wrapper
│   ├── deploy-frontend.sh# 프론트엔드 정적 자산 sync + invalidation
│   └── iam/              # GitHub Actions OIDC trust + permission policy
├── .github/workflows/    # backend-deploy / frontend-deploy / pr-check
├── docs/deploy.md        # 운영 빠른 참조
└── CLAUDE.md             # AI 에이전트용 프로젝트 컨텍스트·진행 로드맵
```

## 기술 스택

### Backend (`backend/`)

- **Java 25**, Gradle wrapper (시스템 JDK 무관 — toolchain 자동 프로비저닝)
- **Spring Boot 4.0.6** + Spring Framework 7 + Jackson 3
- **Spring Cloud Function 4.3.0** + AWS Lambda adapter — 단일 jar 가 API + 향후 cron 핸들러 호스팅
- **AWS SDK v2** — DynamoDB, SSM, URL connection client
- **JUnit 5** + testcontainers(`amazon/dynamodb-local`) + WireMock(KIS 응답 스텁)

### Frontend (`frontend/`)

- **Vite 7** + **React 19** + **TypeScript 6**
- **vite-plugin-pwa** (Workbox)
- **recharts** — 파이 / 라인 차트
- **@tanstack/react-query** — 서버 상태 관리·캐싱
- **react-router-dom** — 4 라우트 (`/`, `/trades`, `/snapshots`, `/history`), `React.lazy` 코드 분할
- **tailwindcss v4** (`@tailwindcss/vite`)
- **html-to-image** — 대시보드 PNG 내보내기

### 인프라

- **AWS SAM** 단일 스택 (`infra/template.yaml`)
- **DynamoDB** — 단일 테이블 `Portfolio` (PITR + GSI1 + TTL)
- **AWS Lambda** — `shadowJar` (Spring Boot fat jar 의 nested lib 문제 회피)
- **API Gateway REST** — Usage plan + API key + throttle/quota
- **S3 + CloudFront** — 정적 자산, OAC 사용, **KR 외 지역 차단**(GeoRestriction)
- **CloudFront Function** — Basic Auth 게이트 (sha256 해시 SSM 보관) + SPA 리라이트
- **CloudWatch Logs** (14일 보존) + **SNS** 이메일 알람 (`Lambda Errors > 0`)
- **GitHub Actions OIDC** 자동 배포 (장기 액세스 키 미사용)

## 외부 의존

- **한국투자증권 OpenAPI** — 미국 주식 시세 + USD/KRW 환율 단일 채널
  - OAuth2 access token (24h) → DynamoDB 박제 후 콜드 스타트 시 재사용
  - 정규장 시간 외(KST 10:00~17:30): 주간장 코드(BAY/BAQ/BAA) 자동 매핑
- 환율 fallback: `frankfurter.app` (KIS 환율 추출 실패 시)

## 데이터 모델 (DynamoDB 단일 테이블)

| 엔티티 | PK | SK | 비고 |
| --- | --- | --- | --- |
| 거래 | `USER#me` | `TRADE#<isoTs>#<uuid>` | append-only. BUY/SELL/DIVIDEND 는 GSI1 키 박제 |
| 보유 포지션 (캐시) | `USER#me` | `POSITION#<ticker>` | 거래에서 파생 |
| USD 현금 잔고 | `USER#me` | `CASH#USD` | 거래에서 파생 |
| 스냅샷 | `USER#me` | `SNAPSHOT#<isoDate>` | 같은 날짜 덮어쓰기 |
| 종목 META | `TICKER#<sym>` | `META` | 거래소 + 연속 실패 카운터 |
| 시세 캐시 | `TICKER#<sym>` | `QUOTE#yyyyMMddHHmm` | KST 10분 슬롯, TTL 1h |
| KIS 토큰 | `META#kis` | `ACCESS_TOKEN` | TTL = expiresAt |

## 자주 쓰는 명령

```bash
# Backend (cwd = repo root, -p 로 프로젝트 지정)
backend/gradlew -p backend bootRun
backend/gradlew -p backend test
backend/gradlew -p backend build           # shadowJar 포함
backend/gradlew -p backend test --tests '*ClassName.methodName'

# Frontend
npm --prefix frontend install
npm --prefix frontend run dev               # 개발 서버 (vite proxy /api → :8080)
npm --prefix frontend run build             # frontend/dist
npm --prefix frontend run preview           # 빌드 산출물 로컬 확인
```

Java 25 toolchain 첫 실행 시 Gradle 이 JDK 를 자동 다운로드.

## 배포

`master` push → GitHub Actions 가 자동 트리거:

- `backend/**` 또는 `infra/template.yaml` 변경 → **backend-deploy** (test → shadowJar → SAM deploy)
- `frontend/**` 변경 → **frontend-deploy** (npm ci → build → S3 sync → CloudFront invalidation)
- PR → **pr-check** (build/test 만, 배포 안 함)

수동 deploy 가 필요하면 `infra/deploy.sh` (SSM 의 Basic Auth 해시 자동 주입). 운영 빠른 참조는 [`docs/deploy.md`](docs/deploy.md), 절차 상세는 [`infra/README.md`](infra/README.md).

## 보안

다층 방어:

1. **CloudFront GeoRestriction** — KR 외 지역 차단 (edge 단계)
2. **Basic Auth** — viewer-request function 의 sha256 해시 비교 (해시는 SSM SecureString)
3. **API Gateway API Key** — 개별 API 호출 인증
4. **Usage Plan throttle/quota** — RPS 10 / day 5000
5. **IAM 좁힌 권한** — DDB/SSM/KMS 모두 자원 ARN 한정, GitHub OIDC 는 master 브랜치 sub claim 한정
6. **CORS Allow-Origin** — CloudFront 도메인 고정
7. **5xx 응답 메시지** — `traceId` 만 노출, stacktrace 는 CloudWatch Logs 만
8. **Dependabot** — gradle / npm / github-actions 매주 자동 PR

상세 보안 audit 결과는 git log 의 `057ef29` (감사 후속 조치) 참조.

## 더 많은 정보

- **[SETUP.md](SETUP.md)** — **처음 사용하는 경우 여기부터** — 사전 준비부터 첫 거래 입력까지 순차 가이드 (30~60분)
- **[CLAUDE.md](CLAUDE.md)** — 진행 로드맵, 완료 항목, 다음 단계, 핵심 결정사항. AI 에이전트용 컨텍스트지만 사람이 읽기에도 정확
- **[docs/deploy.md](docs/deploy.md)** — 운영 빠른 참조 (이미 배포된 상태에서 코드 변경 후 재배포 흐름)
- **[infra/README.md](infra/README.md)** — IAM 사전 권한, deploy wrapper 사용법, 회전 절차
- **[infra/iam/README.md](infra/iam/README.md)** — GitHub Actions OIDC Role 생성 명령

## 개인 프로젝트 (1인용)

이 저장소는 단일 사용자(=저자) 본인의 포트폴리오 관리에 특화돼 있습니다. 멀티테넌시·복잡한 권한 모델·과한 옵저버빌리티 스택은 의도적으로 도입하지 않았습니다. 비슷한 코드 3줄은 그대로 두는 편이 섣부른 추상화보다 낫다는 원칙을 따릅니다.
