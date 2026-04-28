package com.example.stockportfolio.adapter.web;

import com.example.stockportfolio.domain.PortfolioRepository;

import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 IT: DynamoDbClient는 MockitoBean으로 무력화하고, PortfolioRepository는 인메모리 fake로 교체.
 * MockMvc는 standalone이 아닌 WebApplicationContext 기반으로 띄워 ExceptionHandler까지 동작시킨다.
 */
@SpringBootTest
class PortfolioControllerIT {

    @TestConfiguration
    static class TestConfig {
        // 같은 이름의 DynamoDbConfig#portfolioRepository와 충돌하지 않도록 다른 빈 이름 사용
        @Bean
        @Primary
        PortfolioRepository inMemoryPortfolioRepository() {
            return new InMemoryPortfolioRepository();
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
        // 컨텍스트가 캐싱되므로 테스트 간 fake state를 격리하기 위해 매번 리셋한다
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
                .andExpect(jsonPath("$.positions[0].avgCostUsd").value("150.0000"));
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
                .andExpect(jsonPath("$.positions", hasSize(0)));
    }

    @Test
    void GET_trades_limit2는_최신_2건을_역순으로_반환한다() throws Exception {
        // 3건 등록 (executedAt 명시)
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
