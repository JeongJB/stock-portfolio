# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 이 저장소의 모든 마크다운 문서·답변은 **한국어**로 작성한다 (코드 식별자, 명령어, 라이브러리 이름은 영어 유지).

## 프로젝트 개요

사용자의 **개인 미국 주식 포트폴리오 관리** 애플리케이션. 1인 사용 전제이며, 비용 최소화를 위해 **AWS Lambda + DynamoDB 서버리스** 아키텍처를 목표로 한다.

현재 상태(2026-04-28): **P0 전체 소진(P0-1 ~ P0-4d)** — 도메인(거래·현금·포지션), DynamoDB 영속성, Web·Application 레이어, Lambda 어댑터, KIS OpenAPI 시세·환율 어댑터(+ exchangerate.host 폴백), `GET /api/portfolio` 평가액·비중·손익 통합, `POST/GET /api/snapshots` 시계열 박제, **TICKER#<sym>/QUOTE#<KST 날짜> DynamoDB 시세 캐시(36h TTL)**까지 구현·테스트(64개) 완료. 다음 단계 후보: **(1) 실사용 검증 한 주**, **(2) 프론트엔드 React PWA 착수**, **(3) P1 발주**(EOD 자동 스냅샷 / 종목 마스터+GSI1 / 배당·수수료 거래종류 / IRR 등). `planner` 재검토로 진행 단위를 결정.

### 사용자가 명시한 베이스라인 기능

1. 보유 종목 비중 **파이 차트**
2. 사용자가 원할 때 총평가액 **스냅샷** → 시계열 **추이 그래프**
3. **평가자산 vs 순수 투입 자산(원금)** 비교로 손익 가시화
4. 매수/매도 수동 입력 시 **달러(USD) 현금 잔고 자동 반영** 후 총평가액에 포함

추가 기능 발굴은 항상 `planner` 에이전트가 먼저 검토한다.

## 기술 스택

- **Java 25** (Gradle 툴체인이 자동 프로비저닝 — 시스템 JDK가 낮아도 됨)
- **Spring Boot 4.0.6** / **Spring Framework 7** / **Jackson 3**(`tools.jackson.databind.*` import 경로) / `io.spring.dependency-management 1.1.7`
- **Gradle Wrapper** — 항상 `./gradlew` 사용, 시스템 `gradle` 금지
- **JUnit 5** (`useJUnitPlatform()`)
- 베이스 패키지: `com.example.stockportfolio`
- **AWS SDK v2** BOM `2.32.5` — `dynamodb`, `url-connection-client`, `ssm`(KIS appkey/appsecret 보관) 사용
- **Lambda 어댑터**: `spring-cloud-function-adapter-aws 4.3.0` (Spring Cloud BOM `2025.0.0`) — Spring Boot 4.0.6 호환 확인됨. 핸들러 클래스 `org.springframework.cloud.function.adapter.aws.FunctionInvoker`, 함수명 `apiGatewayHandler`
- **DynamoDB 통합 테스트**: testcontainers `amazon/dynamodb-local:2.5.4` (도커 필요 — 사용자 환경은 rancher-desktop)
- **외부 HTTP 어댑터 테스트**: `wiremock-standalone 3.13.1` (KIS OpenAPI 응답 스텁)
- (예정) 프론트엔드 — **React.js + PWA**, **S3 + CloudFront** 정적 호스팅 (백엔드와 별도 디렉터리/리포)

## 자주 쓰는 명령

```bash
./gradlew bootRun                                     # 앱 실행
./gradlew build                                       # 컴파일 + 테스트 + jar 생성
./gradlew test                                        # 전체 테스트
./gradlew test --tests StockPortfolioApplicationTests # 특정 클래스만
./gradlew test --tests '*ClassName.methodName'        # 특정 메서드만
./gradlew clean
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

- 시크릿/AWS 자격 증명·한투 API 키는 코드·커밋에 절대 포함하지 않는다 (런타임은 환경변수 또는 Secrets Manager).
- 우선순위 P1/P2 후보(EOD 자동 스냅샷 모드, 종목 마스터+GSI1, 배당, 실현·평가 손익 분리, IRR, 백업/내보내기 등)는 P0 안정화 후 다시 `planner`로 검토.
- **운영 배포 1회 작업**: DynamoDB 콘솔에서 `Portfolio` 테이블의 TTL 속성을 `ttl`로 활성화해야 시세 캐시 만료 항목이 자동 청소된다. 활성화 전에도 캐시 hit/miss 로직 자체는 정상 동작하나 누적 비용이 점진적으로 증가.
