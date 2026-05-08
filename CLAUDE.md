# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 이 저장소의 모든 마크다운 문서·답변은 **한국어**로 작성한다 (코드 식별자, 명령어, 라이브러리 이름은 영어 유지).

## 프로젝트 개요

사용자의 **개인 미국 주식 포트폴리오 관리** 애플리케이션. 1인 사용 전제이며, 비용 최소화를 위해 **AWS Lambda + DynamoDB 서버리스** 아키텍처를 목표로 한다.

**저장소 구조 (모노리포)**:

```
.
├── backend/   # Spring Boot 4.0.6 / Java 25 / AWS Lambda + DynamoDB
└── frontend/  # React + Vite + TypeScript / PWA / S3 + CloudFront 정적 호스팅
```

루트의 `.claude/`, `CLAUDE.md`, `.gitignore`, `.gitattributes`는 모노리포 전체에 적용되는 자산이다.

현재 상태(2026-05-01): **백엔드 P0(P0-1 ~ P0-4d) + 프론트엔드 P0-FE(FE-0 ~ FE-3) + FE-4 + FE-5(a/b/c/e/f) 완료 + F-CHECK 실사용 검증 완료** — 베이스라인 기능 1·2·3·4가 dev 환경에서 동작 확인됨. AWS SAM 으로 백엔드 Lambda + API Gateway + DynamoDB + 프론트엔드 S3/CloudFront + 운영 알람(SNS 이메일)까지 IaC 화 + GitHub Actions 자동 배포(OIDC)까지 완료. P1-FE 전구간 종료. 자세한 진척·다음 단계는 [진행 로드맵](#진행-로드맵-재개-가이드) 섹션 참고.

### 사용자가 명시한 베이스라인 기능

1. 보유 종목 비중 **파이 차트**
2. 사용자가 원할 때 총평가액 **스냅샷** → 시계열 **추이 그래프**
3. **평가자산 vs 순수 투입 자산(원금)** 비교로 손익 가시화
4. 매수/매도 수동 입력 시 **달러(USD) 현금 잔고 자동 반영** 후 총평가액에 포함

추가 기능 발굴은 항상 `planner` 에이전트가 먼저 검토한다.

## 진행 로드맵 (재개 가이드)

### 완료

**백엔드 P0 — `backend/`, 64개 테스트 통과**

| 단위 | 산출물 |
| --- | --- |
| P0-1 도메인 | `Portfolio` / `Trade` / `Position` / `Money` / `Quote` 등. 거래 → 현금/포지션 갱신, 가중평균 단가, 실현 손익. |
| P0-2 영속성 | `DynamoPortfolioRepository`(testcontainers IT), 단일 테이블 `Portfolio`. |
| P0-3 Web/Application + Lambda | `PortfolioController`, `PortfolioApplicationService`, `apiGatewayHandler` 함수(Spring Cloud Function). |
| P0-4a KIS OpenAPI | 시세·환율 어댑터, OAuth2 토큰 캐싱, exchangerate.host 폴백. |
| P0-4b 평가액 통합 | `GET /api/portfolio` 응답에 평가액·비중·손익 + USD/KRW 동시 노출. 시세 실패 종목 격리. |
| P0-4c 스냅샷 | `POST/GET /api/snapshots`(같은 날짜 덮어쓰기, 종목 상세 JSON 박제). |
| P0-4d 시세 캐시 | `TICKER#<sym>/QUOTE#<KST 날짜>` 36h TTL 데코레이터. |

**백엔드 P1 — `backend/`**

| 단위 | 산출물 |
| --- | --- |
| P1-1 종목 META + 거래소 자동 탐색 | `TickerMeta` 도메인 + `TickerMetaRepository` 포트 + `DynamoTickerMetaRepository` 어댑터. `ExchangeResolver` 가 ticker→exchange 결정 (META 없음→NAS/NYS/AMS 탐색, 카운터≥3→재탐색). `PortfolioApplicationService` 의 `DEFAULT_EXCHANGE` 하드코딩 제거 + view() 내 자기치유 (성공 시 카운터 0 리셋, 실패 시 +1). 거래 PUT 시 BUY/SELL 만 GSI1 키(`gsi1pk=TICKER#<sym>`, `gsi1sk=TRADE#<isoTs>`) 박제. `listTradesByTicker` 메서드 추가 (P2 대비). cron 미도입 — view() 안에서 자기치유. |
| P1-2 시세 캐시 10분 슬롯화 | DynamoQuoteCache SK 를 `QUOTE#yyyyMMddHHmm` (KST 10분 floor) 로 변경, TTL 36h→1h. QuoteCachePort 시그니처 LocalDate→Instant. 미국 정규장 동안 종목당 ~39콜/일로 늘지만 1인용 호출 빈도엔 KIS 한도 여유. |
| P1-3 application.yml 전환 | application.properties → yml 변환, properties 삭제. 환경별 분기 쉬워짐. |
| P1-4 한투 주간장 EXCD 자동 매핑 | KST 10:00~17:30 사이 시세 조회 시 NYS/NAS/AMS 를 BAY/BAQ/BAA 로 자동 변환. 주간장 응답이 비면 정규장 코드로 1회 fallback. 도메인 `Exchange` enum 과 META 에 박힌 거래소는 정규장 코드 그대로 유지 — 매핑은 어댑터 내부에만. |
| P1-5 DIVIDEND 거래 종류 추가 | TradeType 에 DIVIDEND 추가 (ticker + amount). 응답 시점 환율 일관 적용. 종목별 평가손익에 누적배당 합산. DIVIDEND 거래 PUT 시 GSI1 키도 박제 (BUY/SELL 과 일관). FE TradeForm 4탭 → 5탭. 종목별 누적배당 분리 표시는 P2(FE-6) 로 미룸. |
| P1-6 IRR + 단순 수익률 | `IrrCalculator` 도메인(XIRR Newton-Raphson + bisection fallback). DEPOSIT/WITHDRAW/DIVIDEND 만 외부 현금흐름으로 사용 (BUY/SELL 제외), 마지막 시점 +현재 총평가액 USD. `GET /api/portfolio` 응답에 `irr`, `simpleReturn` 두 필드(nullable) 추가. 대시보드 합계 카드 3→5장 (연환산 수익률·단순 수익률 추가). KRW 환산 안 함 — USD 기준. |
| P1-7 KIS 토큰 DynamoDB 박제 | `KisAccessTokenStore` 포트(`adapter/marketdata/kis/`) + `DynamoKisAccessTokenStore` 어댑터 신설. KIS access token 을 단일 테이블 `META#kis / ACCESS_TOKEN` 항목에 박제(`accessToken`, `expiresAt` ISO Instant, `ttl` epoch second). `KisAccessTokenManager` 가 in-memory → DDB → KIS 3-layer 캐시(refresh margin 60s, race 처리는 단일 인스턴스 가정으로 무시). DDB find/save 예외는 best-effort WARN 후 KIS 폴스루/정상 반환. Lambda 콜드 스타트 시 KIS `/oauth2/tokenP` 호출 안 해도 됨 — 24h 토큰 재사용. SAM template 변경 없음(같은 테이블·기존 IAM 권한). |
| P1-8 DDB 조회 비용 최적화 | `DynamoPortfolioRepository.load()` KeyCondition 에 `#sk < "SNAPSHOT#"` 추가 — SK 사전순(`CASH# < META# < POSITION# < SNAPSHOT# < TRADE#`)을 활용해 거래·스냅샷 항목을 아예 안 읽음. 모든 풀범위 Query(`load`·`listAllTrades`·`listTradesByType`·`findSnapshots`)에 `queryAllPages` / `queryUpTo` 헬퍼로 `LastEvaluatedKey` 페이지네이션 도입(이전엔 1MB 응답에서 silent 잘림 위험). `PortfolioApplicationService.view()` 가 `listTradesByType(DIVIDEND/DEPOSIT/WITHDRAW)` 3회 + DIVIDEND 중복까지 거래 풀스캔 4회였던 것을 `listAllTrades` 1회 + 인메모리 type 분기로 통합 — `computeIrr` 시그니처도 `List<Trade>` 입력으로 변경. 거래 누적 시 매 호출 비용이 거래 수에 무관(load)하거나 1회만 비례하도록. 데이터 마이그레이션 불필요. |
| P1-9 종목 sector 자유 입력 + sector 별 비중 토글 | `TickerMeta` 에 `sector: String?` 추가(옛 항목 자연 호환). `RecordTradeCommand`/`RecordTradeRequest` 의 BUY 케이스에 `sector` 추가(optional, ≤30 chars, trim/blank→null). BUY 거래 처리 후 `persistSectorBestEffort` 가 거래 트랜잭션과 분리한 별도 PutItem 으로 META 갱신(try/catch + WARN, 거래 실패 안 시킴) — null 입력은 기존 sector 유지. `ExchangeResolver.resolveWithMeta()` 신규로 `view()` 가 추가 GetItem 없이 sector 까지 전파. `PositionView` 에 sector 노출, 신규 endpoint·집계 필드 없음. FE: `TradeForm` BUY 탭에 `<input list="sector-suggestions">` + `<datalist>` 자동완성(positions[*].sector distinct), 보유 종목 선택/blur 시 pre-fill. `AllocationTreemap` 우상단에 segmented control(`localStorage allocation-mode`, 기본 종목별), sector 모드는 `(sector ?? "분류 미지정")` 인메모리 reduce + 현금 별도 "현금" 슬라이스, 시세 실패 종목 합산 제외, 색상은 FNV-1a `hash(label)→hsl(h,65%,55%)` deterministic, 라벨 `sector명 ({percent}%)`, 툴팁 3줄(`sector명 / N개 종목 / 합계 평가액`). 동일값 Put skip 분기·일괄 재분류 endpoint·도입 안내 토스트 미도입. 마이그레이션 불필요. |

**프론트엔드 P0-FE — `frontend/`, Vite 7 + React 19 + TS 6 + PWA + Tailwind v4**

| 단위 | 산출물 |
| --- | --- |
| FE-0 공통 인프라 | `api/types.ts`(BigDecimal=string, nullable 명시), `api/client.ts`(`VITE_API_BASE_URL` / `VITE_API_KEY`), USD/KRW 컨텍스트(localStorage 영속·기본 KRW), KST 시각·천 단위 포맷 헬퍼, 자체 토스트 시스템, vite dev proxy(`/api` → `:8080`). |
| FE-1 대시보드 (`/`) | 평가액·원금·평가손익·IRR·단순 수익률 합계 카드 5장 / 보유 비중 파이 차트(현금 슬라이스 포함, 종목별 퍼센트 legend 우측/모바일은 하단) / 포지션 표(시세 실패 행 회색 음영 + `—`) / 우상단 "이미지로 저장" 버튼 (html-to-image PNG, 검은 배경, 파일명 `portfolio-<KST date>.png`). |
| FE-2 거래 입력 (`/trades`) | BUY/SELL/DIVIDEND/DEPOSIT/WITHDRAW 5종 탭, 같은 페이지 머무름 + 토스트, 4xx/5xx 응답 본문 그대로 노출, `executedAt` 토글로 과거 시각 입력. (DIVIDEND 는 P1-5 에서 추가) |
| FE-3 스냅샷 추이 (`/snapshots`) | 평가액·원금 두 라인 차트(USD/KRW 즉시 토글), 호버에 `usdKrwRate` 표시, 박제 시 갱신/저장 분기 토스트, 0건 빈 상태 안내. 기간 선택(1달/3달/6달/1년/5년/사용자 지정, 디폴트 1달, localStorage 영속). 차트 위에 기간 평가액 상승률 카드 ((최근 - 가장 오래된)/가장 오래된, 입금·출금 미보정 단순 변화율). |

**프론트엔드 P1-FE — `frontend/` + `infra/`**

| 단위 | 산출물 |
| --- | --- |
| FE-4 PWA 아이콘·매니페스트 마무리 | treemap 모티프 6박스 형형색색 아이콘(`favicon.svg` / `master-maskable.svg` 마스터 + `pwa-192.png` / `pwa-512.png` / `pwa-maskable-512.png` purpose 분리), 매니페스트 `id`·`scope`·`lang`·`categories`·`display_override` 보강, `includeAssets` 누락 자산 정리. iOS 자산은 의도적 제외(Android 단독 사용). |
| FE-5a/b/c IaC + 배포 | AWS SAM `infra/template.yaml` — DynamoDB(PITR + GSI1, 소문자 `pk/sk`) / Lambda(`shadowJar` + `META-INF/spring/*.imports` 라인 병합) / API Gateway(`/api/{proxy+}` GET·POST·OPTIONS 분리, OPTIONS 만 ApiKeyRequired:false) / Usage plan + API key / S3 비공개 + CloudFront + OAC + SPA viewer-request 리라이트. 프론트 배포는 `infra/deploy-frontend.sh` (assets long-cache, 그 외 no-cache, invalidate). |
| FE-5f 운영 가시성 | CloudWatch LogGroup 14일 보존 + SNS Topic/이메일 구독(`surpatience@gmail.com`) + Lambda `Errors > 0`(5분, `notBreaching`) 단일 알람. `docs/deploy.md` 1페이지 빠른 참조 신규. 추가 메트릭 알람은 1인용에 과하므로 도입 안 함. |
| FE-5e GitHub Actions 자동 배포 | 워크플로 3개(backend-deploy / frontend-deploy / pr-check) + OIDC IAM Role + 권한·trust policy JSON 리포 박제. master push 자동 배포, PR 은 build/test 만. `AWS_ROLE_ARN` 1개만 GitHub Secret. `VITE_API_KEY` 는 Secrets 미보관 — 워크플로 런타임에 API Gateway 에서 즉시 추출 후 add-mask. |

**프론트엔드 P2 — `frontend/` + `backend/`**

| 단위 | 산출물 |
| --- | --- |
| FE-6 거래 내역 표 + memo | `/history` 신규 페이지 + 헤더 메뉴 "거래 내역". `GET /api/trades?limit=200` (최신순, default=200/MAX=1000). 컬럼 7종(시각·종류·종목·수량·단가·금액·비고). 거래 종류별 색상 배지(BUY emerald / SELL rose / DEPOSIT sky / WITHDRAW slate / DIVIDEND violet). 입금·출금 거래에 비고(memo) 입력·표시 추가 — `Trade` 도메인에 `memo: String?` 필드 신설(최대 200자, blank→null 정규화), FE 입력은 DEPOSIT/WITHDRAW 폼에만 노출. 거래 시점 환율 미박제 → 표는 USD 기준만 표시. 편집·필터·페이지네이션은 미도입. 삭제는 행 휴지통 아이콘 + 확인 모달 → `DELETE /api/trades/{id}` (백엔드가 현재 상태에서 거래 효과 역산, BUY/SELL 은 해당 ticker 만 local replay 로 Position 재계산, 결과가 음수 잔고·포지션이면 422. backfill 패턴(과거 시각 거래 + 최근 시각 입금) 도 정상 처리). |
| FE-7 + FE-8 거래 폼 보유 종목 선택 | TradeForm 의 SELL/DIVIDEND 탭은 strict dropdown(보유 종목만), BUY 탭은 radio 토글(신규 매수=자유 입력 / 추가 매수=dropdown). 선택 시 평균단가·수량 힌트. 0개 보유 시 안내 메시지. 데이터는 `GET /api/portfolio` 의 positions[] (react-query 캐시 공유 — 추가 fetch X). 백엔드 변경 없음. |
| infra: KR-only GeoRestriction | CloudFront `DistributionConfig.Restrictions.GeoRestriction = whitelist [KR]`. 한국 외 IP 는 CloudFront 단계에서 403. API Gateway 는 별도 차단 안 함 (API Key + 정상 호출 흐름이 프론트 경유라 우회 가치 낮음, WAF $5/월 회피). 해외 출장 시 일시 허용은 template Locations 추가 + redeploy. |
| FE: 라우트 기반 코드 분할 | `App.tsx` 의 4개 페이지(Dashboard/Trades/Snapshots/History)를 `React.lazy` + `<Suspense>` 로 동적 import. recharts(`CategoricalChart`)는 Dashboard/Snapshots 가 공유하므로 vite/rollup 이 자동으로 별도 shared 청크(~320KB)로 분리. 메인 청크 695KB → 261KB(메인 vendor) + 페이지별 4~51KB, 빌드 경고(`Some chunks are larger than 500 kB`) 해소. PWA precache 총량은 비슷 — 첫 방문 1회 성능 향상 목적. Suspense fallback 은 기존 "로딩 중..." 텍스트와 동일한 패턴. |
| FE-9 모바일 레이아웃 (다크모드·접근성 제외) | 모바일 375~414px 가정 보강. 표(포지션·거래 내역)는 `overflow-x-auto` 가로 스크롤 + 셀 `whitespace-nowrap`, 거래 내역은 첫 컬럼(시각) `sticky left-0` 으로 행 식별 유지. 헤더 nav 4개는 패딩/텍스트 축소(`text-lg sm:text-2xl`, `gap-x-3`)로 한 행 유지(햄버거 미도입). TradeForm 5탭은 외부 wrapper `overflow-x-auto`, 탭 `whitespace-nowrap`. 입력 필드 `inputMode="decimal"` (기존 유지) + 주요 버튼 `min-h-[44px]` (Apple HIG). `index.html` viewport `viewport-fit=cover`, `body { overflow-x: hidden }` 로 의도치 않은 가로 스크롤 0. 다크모드와 접근성 a11y 보강은 1인용 가치 작아 보류. |
| FE: in-app 설치 버튼 | 헤더 우측에 PWA 설치 버튼. `useInstallPrompt` 훅이 `beforeinstallprompt` 이벤트를 잡아 저장, 클릭 시 native install dialog. 이미 설치되었거나(`display-mode: standalone`) 이벤트가 발생 안 한 환경(iOS Safari 등)엔 버튼 자동 숨김. Android Chrome 의 자동 prompt 가 환경에 따라 안 뜨는 케이스 보완. |
| FE: 손익 페이지 | `/pnl` 신규 페이지 + 헤더 메뉴 "손익". PeriodSelector(스냅샷과 동일 6옵션) + 월별/연별 토글. SELL 의 실현 손익 + DIVIDEND 합산을 KST 기준 월/연 단위로 그룹. USD 만 표시. 백엔드는 `TradeView` 에 `realizedPnlUsd` 필드 추가 (SELL 만, 시간순 평균단가 기반 계산). |
 
### 다음 단계 (재개 시 이 순서)

1. **백엔드 P1 발주** *(현재 발주 후보 없음 — 새 요구사항 발생 시 `planner` 재검토)*.
2. **P2 후속**:
   - SnapStart 적용 검토 — 현재 콜드 스타트 5~10초 수용 중. 1인용 빈도엔 체감 거슬리지 않음 → 우선순위 낮음.
   - X-Ray / 추가 알람(요청량·throttle 메트릭) — 1인용엔 과함. 정말 필요해질 때만.

### 운영 1회 작업 (FE-5e 적용 시 사용자가 처리할 것)

1. `aws iam create-role` + `put-role-policy` (`infra/iam/` JSON 사용 — `<ACCOUNT_ID>` 치환 후). 상세 명령은 [infra/iam/README.md](infra/iam/README.md).
2. `gh secret set AWS_ROLE_ARN --repo JeongJB/stock-portfolio --body "..."`.
3. 첫 master push → Actions 탭에서 두 워크플로 자동 트리거 확인.

## 기술 스택

### Backend (`backend/`)

- **Java 25** (Gradle 툴체인이 자동 프로비저닝 — 시스템 JDK가 낮아도 됨)
- **Spring Boot 4.0.6** / **Spring Framework 7** / **Jackson 3**(`tools.jackson.databind.*` import 경로) / `io.spring.dependency-management 1.1.7`
- **Gradle Wrapper** — 항상 `backend/gradlew` 사용, 시스템 `gradle` 금지
- **JUnit 5** (`useJUnitPlatform()`)
- 베이스 패키지: `com.example.stockportfolio`
- **AWS SDK v2** BOM `2.32.5` — `dynamodb`, `url-connection-client`, `ssm`(KIS appkey/appsecret 보관) 사용
- **Lambda 어댑터**: `spring-cloud-function-adapter-aws 4.3.0` (Spring Cloud BOM `2025.0.0`) — Spring Boot 4.0.6 호환 확인됨. 핸들러 클래스 `org.springframework.cloud.function.adapter.aws.FunctionInvoker`, 함수명 `apiGatewayHandler`
- **DynamoDB 통합 테스트**: testcontainers `amazon/dynamodb-local:2.5.4` (도커 필요 — 사용자 환경은 rancher-desktop)
- **외부 HTTP 어댑터 테스트**: `wiremock-standalone 3.13.1` (KIS OpenAPI 응답 스텁)

### Frontend (`frontend/`)

- **Vite + React + TypeScript** — Vite는 CRA를 대체하는 표준 번들러
- **PWA** — `vite-plugin-pwa` (Workbox 기반)
- **차트**: `recharts` (파이/라인 모두 React 친화적)
- **데이터 패칭**: `@tanstack/react-query` (캐싱·로딩 상태 단순화)
- **라우팅**: `react-router-dom`
- **스타일**: `tailwindcss` v4 (`@tailwindcss/vite` 플러그인)
- **상태**: `useState`/`useReducer`만 (1인용에 Redux/Zustand 과잉)

## 자주 쓰는 명령

```bash
# Backend (cwd = repo root, 별도 디렉터리 진입 없이 -p 로 지정)
backend/gradlew -p backend bootRun
backend/gradlew -p backend build
backend/gradlew -p backend test
backend/gradlew -p backend test --tests StockPortfolioApplicationTests
backend/gradlew -p backend test --tests '*ClassName.methodName'
backend/gradlew -p backend clean

# Frontend (cwd = repo root, --prefix 로 지정)
npm --prefix frontend install
npm --prefix frontend run dev      # 개발 서버
npm --prefix frontend run build    # 정적 빌드 출력 → frontend/dist
npm --prefix frontend run preview  # 빌드 산출물 로컬 확인
```

Java 25 툴체인 첫 실행 시 Gradle이 JDK를 다운로드할 수 있어 시간이 소요될 수 있다.

## 에이전트 협업 흐름

이 저장소에는 두 개의 프로젝트 전용 서브에이전트가 정의돼 있다 (`.claude/agents/`):

- **`planner`** — 기능 기획·요구사항 정제. 사용자 스토리, 인수 조건, 우선순위(P0/P1/P2), DynamoDB 데이터 모델 초안을 한국어로 산출. **코드를 작성하지 않는다.**
- **`developer`** — `planner` 산출물을 입력으로 받아 실제 코드를 작성. 명세 없이 임의 기능을 추가하지 않으며, 모호한 부분은 planner에게 되돌린다.

새 기능 요청 처리 절차:

1. `planner`로 명세·우선순위 정리 → 사용자 검토
2. 합의된 P0 항목을 `developer`에게 위임
3. `developer`는 변경 후 `./gradlew test`(또는 `build`)로 검증한 결과까지 함께 보고

## 설계 제약 (의사결정 시 우선순위)

- **비용**: 서버리스 무료 티어/저비용 우선. 상시 가동 인스턴스, 비싼 매니지드 서비스, 과한 옵저버빌리티 스택은 보류.
- **단순함**: 1인 사용. 멀티테넌시·복잡한 권한 모델·과한 추상화 금지. 비슷한 코드 3줄은 그대로 두는 편이 섣부른 추상화보다 낫다.
- **수동 입력 + 자동 파생값**: 매수/매도는 사람이 입력, 현금 잔고·평가액·손익은 시스템이 계산. 입력과 파생값의 책임을 섞지 않는다.
- **시계열 보존**: 스냅샷·거래 내역은 append-only로 다룬다. DynamoDB SK에 타임스탬프를 포함해 시계열 조회를 자연스럽게 만든다.

## 확정된 핵심 결정 (2026-04-28)

- **시세 + 환율 소스**: **한국투자증권 OpenAPI** 단일 채널 (미국 주식 시세, USD/KRW 환율 모두). OAuth2 access token 약 24시간 만료 → 갱신 로직 필요.
- **통화 표시**: **KRW / USD 토글**. 데이터는 USD 기준 저장, 스냅샷 시 환율(`usdKrwRate`)도 함께 보존해 과거 KRW 환산 일관성 유지.
- **응답 형태**: `GET /api/portfolio` 등 응답에 USD 값과 KRW 환산값을 **함께** 싣는다(프론트에서 별도 환산 불필요). 환율은 응답 1건마다 `MarketDataPort.getUsdKrwRate()` 1회 호출로 일관 적용.
- **스냅샷**: 같은 날짜 재호출은 **덮어쓰기**(append-only는 거래에만 적용). **합계 메트릭만 박제** — 종목 상세(positions)는 박제하지 않으며 응답에도 노출하지 않는다 (FE 차트가 합계만 사용 + 항목 1건당 사이즈 ~6KB → ~700B 절감).
- **인증**: API Gateway + 단일 API key (1인용).
- **콜드 스타트**: 별도 대응 없음 (SnapStart/AOT 미적용).
- **스냅샷 트리거**: 사용자가 원할 때 `POST /api/snapshots` 로 수동 적립.
- **인프라 정의**: AWS SAM (`infra/template.yaml`) 단일 스택으로 관리. DynamoDB(PITR+GSI1) / Lambda(shadowJar) / API Gateway(usage plan + API key) / S3 + CloudFront + OAC + SPA 리라이트 / CloudWatch LogGroup(14일) / SNS Topic + Lambda Errors 알람까지 IaC 화. 운영 빠른 참조는 [docs/deploy.md](docs/deploy.md), 절차 상세는 [infra/README.md](infra/README.md).
- **비용·brute force 가드레일** (WAF 대체): `ApiThrottleRateLimit=5 / BurstLimit=10 / ApiQuotaPerDay=2000` 으로 Usage plan 강화 + `LambdaReservedConcurrency=1` (한도 증액 1000 적용 완료) + `AWS::Budgets::Budget` 월 $5 (ACTUAL 80% / FORECASTED 100%) 이메일 알림. WAF 미사용은 의도적 — 1인용에서 비용 0 으로 동등 효과. CloudFront GeoRestriction(KR-only) + Basic Auth 와 함께 4-layer 방어.
- **타임존**: **KST (Asia/Seoul)** 기준. Lambda `TZ=Asia/Seoul`, DynamoDB SK 날짜·스케줄 모두 KST.

## 데이터 모델 요약 (planner 산출물)

DynamoDB 단일 테이블 `Portfolio` (PK 사용자 상수 `USER#me`):

| 엔티티                  | PK              | SK                              |
| ----------------------- | --------------- | ------------------------------- |
| 거래                    | `USER#me`       | `TRADE#<isoTs>#<uuid>`          |
| 보유 포지션(파생 캐시)  | `USER#me`       | `POSITION#<ticker>`             |
| USD 현금 잔고           | `USER#me`       | `CASH#USD`                      |
| 스냅샷                  | `USER#me`       | `SNAPSHOT#<isoDate>`            |
| 종목 마스터             | `TICKER#<sym>`  | `META`                          |
| 시세 캐시               | `TICKER#<sym>`  | `QUOTE#<isoDate>` (TTL 만료)    |

GSI1 (`PK=TICKER#<sym>, SK=TRADE#<ts>`)로 종목별 거래 조회. 시세 캐시는 DynamoDB TTL로 자동 만료해 비용 절감.

거래 종류: `DEPOSIT`, `WITHDRAW`, `BUY`, `SELL` (P1에서 `DIVIDEND`, `FEE` 추가). 거래는 append-only, 포지션·현금 잔고는 거래에서 파생되는 캐시.

## 메모

- 시크릿/AWS 자격 증명·한투 API 키는 코드·커밋에 절대 포함하지 않는다 (런타임은 환경변수 또는 SSM Parameter Store / Secrets Manager).
- P1/P2 후보 목록과 운영 1회 작업은 [진행 로드맵](#진행-로드맵-재개-가이드) 섹션에서 일원 관리한다.
- **Lambda 배포는 `shadowJar`** 사용 (bootJar 의 `BOOT-INF/lib/*` 중첩을 Lambda 가 못 펼침) + `build.gradle` 의 `doLast` 가 `META-INF/spring/*.imports` 를 라인 병합한다.
- **DynamoDB 속성 케이스**: 코드와 template KeySchema 모두 소문자 `pk/sk/gsi1pk/gsi1sk` 로 통일 (대소문자 섞이면 `Query condition missed key schema element` 발생).
- **API Gateway CORS**: SAM `Method: ANY` 로 묶으면 OPTIONS 도 ApiKeyRequired 가 적용돼 preflight 가 403 → OPTIONS 만 별도 라우트 + `ApiKeyRequired: false` 로 분리해야 함.
- **프론트엔드 Basic Auth**: CloudFront viewer-request 함수가 sha256 해시를 비교해 진입을 게이팅. 평문/base64/해시 모두 repo 에 박지 않고 SSM SecureString `/portfolio/cloudfront/basic-auth-hash` 에 보관, `infra/deploy.sh` 가 deploy 시점에 `BasicAuthHash` 파라미터로 주입. 직접 `sam deploy` 호출 시 default 없는 파라미터라 실패 — 항상 wrapper 사용. 회전 절차는 [docs/deploy.md](docs/deploy.md).
- **API throttle / Lambda concurrency 부수효과**: 5 RPS / burst 10 / 일 2000 으로 capped 됐다. 정상 1인 사용엔 무관하나 본인이 두 탭에서 동시에 새로고침하거나 단기간 큰 query 폭이 발생하면 일시적 429 가 발생할 수 있음. 거슬리면 `ApiThrottleBurstLimit` 만 올려서 redeploy.
- **Lambda Reserved Concurrency Conditional 패턴**: `ReservedConcurrentExecutions=0` 은 "함수 비활성화" 의미라 그대로 쓸 수 없다 — Conditional `HasReservedConcurrency` 로 `LambdaReservedConcurrency=0` 일 때 속성 자체를 부재(`AWS::NoValue`)시켜 unreserved pool 사용. 신규 AWS 계정 한도가 10 인 경우(정상 prod 1000) reserved 1 적용 시 unreserved 9 < AWS hard floor 10 으로 거부되는 함정 회피용. 현재 한도 1000 증액 완료(Service Quotas: Lambda L-B99A9384) 라 디폴트 1 로 운영. 향후 다른 함수에서 다시 만나면 `--parameter-overrides LambdaReservedConcurrency=0` 으로 임시 우회 가능.
- **AWS Budgets**: region-agnostic 이라 ap-northeast-2 스택에 그대로 정의됨. CloudWatch Billing 메트릭(us-east-1 전용)을 일부러 회피한 선택. **첫 배포 후 AWS Billing console 에서 "Receive Billing Alerts" 가 활성인지 1회 확인** — 비활성이면 ACTUAL 메트릭 자체가 publish 안 돼 알림 작동 안 함.
