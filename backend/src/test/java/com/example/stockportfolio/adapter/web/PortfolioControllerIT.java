package com.example.stockportfolio.adapter.web;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.TickerMetaRepository;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 IT: DynamoDbClient는 MockitoBean으로 무력화하고, PortfolioRepository는 인메모리 fake로 교체.
 * MarketDataPort도 stub로 갈음해 외부 호출 없이 동작하도록 한다.
 */
@SpringBootTest
class PortfolioControllerIT {

    private static final BigDecimal STUB_RATE = new BigDecimal("1400");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        PortfolioRepository inMemoryPortfolioRepository() {
            return new InMemoryPortfolioRepository();
        }

        @Bean
        @Primary
        MarketDataPort stubMarketDataPort() {
            MarketDataPort mock = Mockito.mock(MarketDataPort.class);
            Mockito.when(mock.getUsdKrwRate()).thenReturn(STUB_RATE);
            Mockito.when(mock.getQuote(eq("AAPL"), any(Exchange.class)))
                    .thenAnswer(inv -> new Quote("AAPL", Exchange.NAS,
                            Money.of("200", Currency.USD), Instant.parse("2026-04-28T00:00:00Z")));
            return mock;
        }

        @Bean
        @Primary
        TickerMetaRepository inMemoryTickerMetaRepository() {
            // ConcurrentHashMap fake — DynamoDbClient mock 의 null 응답에 의존하지 않도록 격리.
            java.util.Map<String, TickerMeta> store = new java.util.concurrent.ConcurrentHashMap<>();
            return new TickerMetaRepository() {
                @Override public java.util.Optional<TickerMeta> find(String ticker) {
                    return java.util.Optional.ofNullable(store.get(ticker));
                }
                @Override public void save(TickerMeta meta) { store.put(meta.ticker(), meta); }
            };
        }
    }

    @MockitoBean
    @SuppressWarnings("unused")
    private DynamoDbClient dynamoDbClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private com.example.stockportfolio.domain.PortfolioRepository repository;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ((InMemoryPortfolioRepository) repository).reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void POST_DEPOSIT은_201과_view에_cash와_principal을_반영한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"1000.00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tradeId").exists())
                .andExpect(jsonPath("$.type").value("DEPOSIT"));

        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashUsd").value("1000.0000"))
                .andExpect(jsonPath("$.principalUsd").value("1000.0000"))
                .andExpect(jsonPath("$.cumulativeDepositUsd").value("1000.0000"))
                .andExpect(jsonPath("$.cumulativeWithdrawUsd").value("0.0000"))
                // 현금만 있는 경우 cashWeight = 1.0
                .andExpect(jsonPath("$.cashWeight").value("1.000000"))
                .andExpect(jsonPath("$.cashKrw").value("1400000.0000"))
                .andExpect(jsonPath("$.principalKrw").value("1400000.0000"))
                .andExpect(jsonPath("$.usdKrwRate").value("1400"))
                .andExpect(jsonPath("$.totalMarketValueUsd").value("0.0000"))
                .andExpect(jsonPath("$.positions", hasSize(0)));
    }

    @Test
    void POST_BUY는_201과_position을_반영한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"10000"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","ticker":"AAPL","qty":"10","price":"150","fee":"1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("BUY"));

        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                // 10000 - (150*10 + 1) = 8499
                .andExpect(jsonPath("$.cashUsd").value("8499.0000"))
                .andExpect(jsonPath("$.positions", hasSize(1)))
                .andExpect(jsonPath("$.positions[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.positions[0].qty").value("10.000000"))
                .andExpect(jsonPath("$.positions[0].avgCostUsd").value("150.0000"))
                // 시세 stub: 200 USD → 평가액 2000, 미실현손익 (200-150)*10 = 500
                .andExpect(jsonPath("$.positions[0].lastPriceUsd").value("200.0000"))
                .andExpect(jsonPath("$.positions[0].lastPriceKrw").value("280000.0000"))
                .andExpect(jsonPath("$.positions[0].marketValueUsd").value("2000.0000"))
                .andExpect(jsonPath("$.positions[0].marketValueKrw").value("2800000.0000"))
                .andExpect(jsonPath("$.positions[0].unrealizedPnlUsd").value("500.0000"))
                .andExpect(jsonPath("$.positions[0].unrealizedPnlKrw").value("700000.0000"))
                // weight: 2000 / (2000 + 8499) = 0.190494
                .andExpect(jsonPath("$.positions[0].weight").value("0.190494"))
                // cashWeight: 8499 / 10499 = 0.809506
                .andExpect(jsonPath("$.cashWeight").value("0.809506"))
                .andExpect(jsonPath("$.totalMarketValueUsd").value("2000.0000"))
                .andExpect(jsonPath("$.totalCostBasisUsd").value("1500.0000"))
                .andExpect(jsonPath("$.totalUnrealizedPnlUsd").value("500.0000"));
    }

    @Test
    void GET_portfolio_응답에_asOf와_KST_오프셋이_포함된다() throws Exception {
        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                // KST = +09:00. ISO-8601 OffsetDateTime 표현
                .andExpect(jsonPath("$.asOf", matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\\+09:00$")));
    }

    @Test
    void POST_BUY는_잔고_부족_시_400과_에러_메시지를_반환한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","ticker":"AAPL","qty":"10","price":"150"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("domain_error"))
                .andExpect(jsonPath("$.message", equalTo("현금 잔고 부족: 0.0000 USD < 1500.0000 USD")));
    }

    @Test
    void POST_잘못된_type은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"INVALID","cashAmount":"100"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_type_누락은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cashAmount":"100"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void GET_portfolio_빈_상태는_cash_0과_빈_positions를_반환한다() throws Exception {
        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashUsd").value("0.0000"))
                .andExpect(jsonPath("$.principalUsd").value("0.0000"))
                .andExpect(jsonPath("$.positions", hasSize(0)))
                // 분모 0인 경우 weight 0
                .andExpect(jsonPath("$.cashWeight").value("0.000000"));
    }

    @Test
    void GET_trades_limit2는_최신_2건을_역순으로_반환한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"DEPOSIT","executedAt":"2026-01-01T00:00:00Z","cashAmount":"100"}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"DEPOSIT","executedAt":"2026-01-02T00:00:00Z","cashAmount":"200"}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"DEPOSIT","executedAt":"2026-01-03T00:00:00Z","cashAmount":"300"}
                        """)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/trades").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cashAmount").value("300.0000"))
                .andExpect(jsonPath("$[1].cashAmount").value("200.0000"));
    }

    @Test
    void GET_trades_limit_초과는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/trades").param("limit", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void POST_DEPOSIT_memo는_저장_후_GET_trades_에서_그대로_반환된다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"500","memo":"초기 시드머니"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/trades").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$[0].memo").value("초기 시드머니"));
    }

    @Test
    void POST_WITHDRAW_memo_blank는_저장_시_attribute_부재로_GET에서_누락된다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"500"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"WITHDRAW","cashAmount":"100","memo":"   "}
                                """))
                .andExpect(status().isCreated());

        // memo 가 blank 였다면 정규화로 null → JsonInclude.NON_NULL 로 응답에서 제외.
        mockMvc.perform(get("/api/trades").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WITHDRAW"))
                .andExpect(jsonPath("$[0].memo").doesNotExist());
    }

    @Test
    void POST_memo_200자_초과는_400을_반환한다() throws Exception {
        String over = "a".repeat(201);
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"100","memo":"%s"}
                                """.formatted(over)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void POST_DIVIDEND는_201과_현금_증가_및_평가손익_누적을_반영한다() throws Exception {
        // 시드: 입금 + 매수 → 현금 8499, AAPL 10주 @150
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"10000"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","ticker":"AAPL","qty":"10","price":"150","fee":"1"}
                                """))
                .andExpect(status().isCreated());

        // DIVIDEND 5 USD 입금
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DIVIDEND","ticker":"AAPL","cashAmount":"5"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DIVIDEND"));

        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                // 현금: 8499 + 5 = 8504
                .andExpect(jsonPath("$.cashUsd").value("8504.0000"))
                // 원금은 영향 없음 (DEPOSIT 만 반영)
                .andExpect(jsonPath("$.principalUsd").value("10000.0000"))
                // AAPL 평가손익 = (200-150)*10 + 5 = 505
                .andExpect(jsonPath("$.positions[0].unrealizedPnlUsd").value("505.0000"))
                .andExpect(jsonPath("$.totalUnrealizedPnlUsd").value("505.0000"));
    }

    @Test
    void POST_DIVIDEND_ticker_누락은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DIVIDEND","cashAmount":"5"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("domain_error"));
    }

    @Test
    void POST_DIVIDEND_amount_0이하는_400을_반환한다() throws Exception {
        // @DecimalMin(value="0", inclusive=false) — 0 또는 음수면 validation_failed
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DIVIDEND","ticker":"AAPL","cashAmount":"0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void POST_snapshots는_현재_view를_박제하고_본문을_반환한다() throws Exception {
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"10000"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","ticker":"AAPL","qty":"10","price":"150","fee":"1"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/snapshots"))
                .andExpect(status().isOk())
                // KST 오늘 날짜로 박제됨 (asOf의 LocalDate)
                .andExpect(jsonPath("$.date", matchesPattern("^\\d{4}-\\d{2}-\\d{2}$")))
                .andExpect(jsonPath("$.takenAt", matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\\+09:00$")))
                .andExpect(jsonPath("$.usdKrwRate").value("1400"))
                .andExpect(jsonPath("$.cashUsd").value("8499.0000"))
                .andExpect(jsonPath("$.totalMarketValueUsd").value("2000.0000"))
                .andExpect(jsonPath("$.totalCostBasisUsd").value("1500.0000"))
                .andExpect(jsonPath("$.totalUnrealizedPnlUsd").value("500.0000"))
                .andExpect(jsonPath("$.positions", hasSize(1)))
                .andExpect(jsonPath("$.positions[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.positions[0].marketValueUsd").value("2000.0000"));
    }

    @Test
    void POST_snapshots_빈_포트폴리오에서도_200을_반환한다() throws Exception {
        mockMvc.perform(post("/api/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashUsd").value("0.0000"))
                .andExpect(jsonPath("$.principalUsd").value("0.0000"))
                .andExpect(jsonPath("$.totalMarketValueUsd").value("0.0000"))
                .andExpect(jsonPath("$.positions", hasSize(0)));
    }

    @Test
    void GET_snapshots_파라미터_없으면_기본_90일_윈도로_200을_반환한다() throws Exception {
        // 박제 한 건 — 오늘 날짜가 자동으로 들어가므로 윈도 안에 포함됨
        mockMvc.perform(post("/api/snapshots")).andExpect(status().isOk());

        mockMvc.perform(get("/api/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots", hasSize(1)));
    }

    @Test
    void GET_snapshots_from_to_지정_시_시계열을_오름차순으로_반환한다() throws Exception {
        // POST /api/snapshots는 KST 오늘 날짜로만 저장하기 때문에, 다중 날짜 시계열 검증은
        // repository에 직접 박제해 검증한다. (덮어쓰기/오름차순/윈도 정확성 확인)
        InMemoryPortfolioRepository repo = (InMemoryPortfolioRepository) repository;
        repo.saveSnapshot(snapshotFor("2026-04-20"));
        repo.saveSnapshot(snapshotFor("2026-04-25"));
        repo.saveSnapshot(snapshotFor("2026-04-28"));
        repo.saveSnapshot(snapshotFor("2026-05-10")); // 윈도 밖

        mockMvc.perform(get("/api/snapshots")
                        .param("from", "2026-04-20")
                        .param("to", "2026-04-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots", hasSize(3)))
                .andExpect(jsonPath("$.snapshots[0].date").value("2026-04-20"))
                .andExpect(jsonPath("$.snapshots[1].date").value("2026-04-25"))
                .andExpect(jsonPath("$.snapshots[2].date").value("2026-04-28"));
    }

    @Test
    void GET_trades_SELL_realizedPnlUsd가_채워지고_나머지_종류는_부재한다() throws Exception {
        // 시드: DEPOSIT(10000) → BUY(10@150) → SELL(5@200, fee=1) → DIVIDEND
        // 실현 손익 = (200-150)*5 - 1 = 249
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","executedAt":"2026-01-01T00:00:00Z","cashAmount":"10000"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","executedAt":"2026-01-02T00:00:00Z","ticker":"AAPL","qty":"10","price":"150"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SELL","executedAt":"2026-01-03T00:00:00Z","ticker":"AAPL","qty":"5","price":"200","fee":"1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DIVIDEND","executedAt":"2026-01-04T00:00:00Z","ticker":"AAPL","cashAmount":"3"}
                                """))
                .andExpect(status().isCreated());

        // 시각 역순 ⇒ [DIVIDEND, SELL, BUY, DEPOSIT]
        mockMvc.perform(get("/api/trades").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].type").value("DIVIDEND"))
                .andExpect(jsonPath("$[0].realizedPnlUsd").doesNotExist())
                .andExpect(jsonPath("$[1].type").value("SELL"))
                .andExpect(jsonPath("$[1].realizedPnlUsd").value("249.0000"))
                .andExpect(jsonPath("$[2].type").value("BUY"))
                .andExpect(jsonPath("$[2].realizedPnlUsd").doesNotExist())
                .andExpect(jsonPath("$[3].type").value("DEPOSIT"))
                .andExpect(jsonPath("$[3].realizedPnlUsd").doesNotExist());
    }

    @Test
    void DELETE_trades_id는_거래를_삭제하고_갱신된_portfolio를_반환한다() throws Exception {
        // 입금만 있는 상태에서 그 입금을 삭제 → 잔고 0
        String body = mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"1000.00"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tradeId = extractTradeId(body);

        mockMvc.perform(delete("/api/trades/" + tradeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashUsd").value("0.0000"))
                .andExpect(jsonPath("$.principalUsd").value("0.0000"));

        mockMvc.perform(get("/api/trades").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void DELETE_trades_없는_id는_404를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/trades/non-existent-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void DELETE_trades_DEPOSIT을_BUY_뒤에_삭제하면_422를_반환한다() throws Exception {
        // DEPOSIT(10000) → BUY(150*10=1500) → 현재 현금 8500.
        // 새 검증(현재 상태 역산): DEPOSIT 10000 삭제 → 8500 - 10000 = -1500 < 0 → 거부.
        String depBody = mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","cashAmount":"10000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String depId = extractTradeId(depBody);

        mockMvc.perform(post("/api/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","ticker":"AAPL","qty":"10","price":"150"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/trades/" + depId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("입금")));

        // 상태 보존: 잔고는 그대로 (10000 - 1500 = 8500)
        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashUsd").value("8500.0000"));
    }

    private static String extractTradeId(String json) {
        // {"tradeId":"...","executedAt":"...","type":"..."} 형태에서 단순 추출.
        int idx = json.indexOf("\"tradeId\":\"");
        if (idx < 0) throw new IllegalStateException("tradeId 누락: " + json);
        int start = idx + "\"tradeId\":\"".length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static com.example.stockportfolio.adapter.web.dto.SnapshotView snapshotFor(String date) {
        java.math.BigDecimal zero = new java.math.BigDecimal("0.0000");
        return new com.example.stockportfolio.adapter.web.dto.SnapshotView(
                java.time.LocalDate.parse(date),
                java.time.OffsetDateTime.parse(date + "T09:00:00+09:00"),
                new java.math.BigDecimal("1400"),
                zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                java.util.List.of());
    }

}
