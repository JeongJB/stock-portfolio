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

현재 상태(2026-04-28): **백엔드 P0(P0-1 ~ P0-4d) + 프론트엔드 P0-FE(FE-0 ~ FE-3) 완료** — 베이스라인 기능 1·2·3·4가 한 번의 dev 환경 실행으로 모두 동작한다. 자세한 진척·다음 단계는 [진행 로드맵](#진행-로드맵-재개-가이드) 섹션 참고.

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

**프론트엔드 P0-FE — `frontend/`, Vite 7 + React 19 + TS 6 + PWA + Tailwind v4**

| 단위 | 산출물 |
| --- | --- |
| FE-0 공통 인프라 | `api/types.ts`(BigDecimal=string, nullable 명시), `api/client.ts`(`VITE_API_BASE_URL` / `VITE_API_KEY`), USD/KRW 컨텍스트(localStorage 영속·기본 KRW), KST 시각·천 단위 포맷 헬퍼, 자체 토스트 시스템, vite dev proxy(`/api` → `:8080`). |
| FE-1 대시보드 (`/`) | 평가액·원금·평가손익 합계 카드 3장 / 보유 비중 파이 차트(현금 슬라이스 포함) / 포지션 표(시세 실패 행 회색 음영 + `—`). |
| FE-2 거래 입력 (`/trades`) | DEPOSIT/WITHDRAW/BUY/SELL 4종 탭, 같은 페이지 머무름 + 토스트, 4xx/5xx 응답 본문 그대로 노출, `executedAt` 토글로 과거 시각 입력. |
| FE-3 스냅샷 추이 (`/snapshots`) | 평가액·원금 두 라인 차트(USD/KRW 즉시 토글), 호버에 `usdKrwRate` 표시, 박제 시 갱신/저장 분기 토스트, 0건 빈 상태 안내. |

### 다음 단계 (재개 시 이 순서)

1. **F-CHECK — 실사용 검증** *(사용자 직접)*: backend bootRun + frontend dev server 를 띄워 화면 흐름을 직접 사용해 본다. UX/문구/색상 이슈가 있으면 별도 미니 발주로 정리. 큰 디자인 변경 없으면 다음 단계로.
2. **FE-4 PWA 아이콘·매니페스트 마무리 (P1-FE)**: 192/512 PNG 아이콘 추가, 매니페스트 보강, iOS/Android "홈 화면에 설치" 동작 검증.
3. **FE-5 배포 파이프라인 (P1-FE)**: `npm --prefix frontend run build` → S3 sync → CloudFront invalidate. 백엔드는 Lambda + API Gateway 배포(JAR 업로드 + `apiGatewayHandler` 매핑). 이 시점에 **IaC 도입(Terraform/SAM/CDK)** 도 함께 검토 — 현재는 콘솔 수동.
4. **백엔드 P1 발주** *(`planner` 재검토 후 1~2개 선택)*:
   - EOD 자동 스냅샷 — EventBridge cron + Lambda 핸들러(`takeSnapshot()` 재사용).
   - 종목 마스터(`TICKER#<sym>/META`) + GSI1 종목별 거래 조회 — 다거래소(NYSE/AMS) 지원도 함께.
   - DIVIDEND / FEE 거래 종류 추가.
   - IRR(내부수익률) 계산.
   - 백업/내보내기.
5. **P2 후속**: FE-6 거래 내역 표(GSI1 도입 후), FE-7 다크모드/접근성/모바일 레이아웃, recharts 청크 분할 등 성능 미세 조정.

### 운영 1회 수동 작업

- **DynamoDB TTL 활성화**: AWS 콘솔에서 `Portfolio` 테이블의 TTL 속성을 `ttl`로 활성화. 활성화 전에도 시세 캐시 hit/miss 로직은 정상 동작하나 만료 항목이 청소되지 않아 누적 비용이 점진적으로 증가.
- **API key·KIS 시크릿 등록**: API Gateway API key, SSM Parameter Store에 KIS appkey/appsecret 등록(파라미터 이름은 `application.properties`의 `kis.appkey-parameter` / `kis.appsecret-parameter` 기본값 또는 환경변수 오버라이드).

### 재개 시 첫 명령

```bash
git log --oneline -10                              # 진척 확인
backend/gradlew -p backend test --console=plain    # 백엔드 회귀 검증
npm --prefix frontend run build                    # 프론트 빌드 검증

# dev 환경 (두 터미널)
backend/gradlew -p backend bootRun                 # :8080
npm --prefix frontend run dev                      # :5173 (vite proxy로 /api → :8080)
```

새 기능은 항상 `planner` → 사용자 검토 → `developer` 흐름. 위 1~5번 다음 단계도 동일.

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
- **스냅샷**: 같은 날짜 재호출은 **덮어쓰기**(append-only는 거래에만 적용). 종목 상세까지 통째로 박제해 후속 분석 여지 보존.
- **인증**: API Gateway + 단일 API key (1인용).
- **콜드 스타트**: 별도 대응 없음 (SnapStart/AOT 미적용).
- **시세 갱신**: 일 1회 EOD (EventBridge cron). EOD 자동 적재는 P1로 미루고 우선 수동 `POST /api/snapshots`로 적립.
- **인프라 정의**: 현재 IaC 파일 없음. DynamoDB 테이블·TTL·Lambda 등 AWS 리소스는 콘솔 수동 관리(P0 안정화 후 IaC 도입 검토).
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
