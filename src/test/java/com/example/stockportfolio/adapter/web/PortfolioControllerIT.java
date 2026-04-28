package com.example.stockportfolio.adapter.web;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Quote;

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
        mockMvc.perform(get("/api/trades").param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

}
