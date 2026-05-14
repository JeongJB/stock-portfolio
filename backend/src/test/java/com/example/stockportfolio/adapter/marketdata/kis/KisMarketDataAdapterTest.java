package com.example.stockportfolio.adapter.marketdata.kis;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.Quote;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class KisMarketDataAdapterTest {

    private WireMockServer wireMock;
    private String baseUrl;
    private RestClient restClient;
    private KisCredentialsProvider credentialsProvider;
    private AtomicReference<Clock> clockHolder;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        // WireMock standalone 과 JDK HttpClient 의 HTTP/2 협상 충돌을 피하기 위해 HttpURLConnection 기반으로 고정.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        restClient = RestClient.builder().requestFactory(factory).build();
        credentialsProvider = () -> new KisCredentialsProvider.KisCredentials("test-key", "test-secret");
        clockHolder = new AtomicReference<>(Clock.fixed(Instant.parse("2026-04-28T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private KisMarketDataAdapter newAdapter() {
        Clock proxyClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockHolder.get().instant();
            }
        };
        KisAccessTokenManager tokenManager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, proxyClock, new InMemoryKisAccessTokenStore());
        KisHttpClient kisHttpClient = new KisHttpClient(restClient, baseUrl, tokenManager, credentialsProvider);
        return new KisMarketDataAdapter(
                kisHttpClient,
                restClient,
                baseUrl,
                "AAPL",
                Exchange.NAS,
                proxyClock,
                new InMemoryFxRateStore());
    }

    private void stubTokenIssue() {
        wireMock.stubFor(post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "ACCESS-1",
                                  "token_type": "Bearer",
                                  "expires_in": 86400,
                                  "access_token_token_expired": "2026-04-29 00:00:00"
                                }
                                """)));
    }

    private void stubQuote(String ticker, String exchange, String last) {
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo(exchange))
                .withQueryParam("SYMB", equalTo(ticker))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "rt_cd": "0",
                                  "msg1": "정상처리",
                                  "output": {
                                    "rsym": "DNAS%s",
                                    "last": "%s",
                                    "base": "%s"
                                  }
                                }
                                """.formatted(ticker, last, last))));
    }

    private void stubFxKisWithRate(String rate) {
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "rt_cd": "0",
                                  "output": {
                                    "last": "150.00",
                                    "t_rate": "%s"
                                  }
                                }
                                """.formatted(rate))));
    }

    private void stubFxKisWithoutRate() {
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "rt_cd": "0",
                                  "output": {
                                    "last": "150.00"
                                  }
                                }
                                """)));
    }

    private void stubFallbackFx(String rate) {
        wireMock.stubFor(get(urlPathEqualTo("/latest"))
                .withQueryParam("from", equalTo("USD"))
                .withQueryParam("to", equalTo("KRW"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "amount": 1.0,
                                  "base": "USD",
                                  "date": "2026-04-28",
                                  "rates": { "KRW": "%s" }
                                }
                                """.formatted(rate))));
    }

    @Test
    void OAuth2_토큰_발급_후_시세_조회() {
        stubTokenIssue();
        stubQuote("AAPL", "NAS", "175.50");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.ticker()).isEqualTo("AAPL");
        assertThat(quote.exchange()).isEqualTo(Exchange.NAS);
        assertThat(quote.price().currency()).isEqualTo(Currency.USD);
        assertThat(quote.price().amount()).isEqualByComparingTo(new BigDecimal("175.50"));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail")));
    }

    @Test
    void 토큰_캐시는_재사용된다() {
        stubTokenIssue();
        stubQuote("AAPL", "NAS", "175.50");
        stubQuote("MSFT", "NAS", "400.00");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);
        adapter.getQuote("MSFT", Exchange.NAS);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail")));
    }

    @Test
    void KIS_환율_정상_추출() {
        stubTokenIssue();
        stubFxKisWithRate("1370.50");
        KisMarketDataAdapter adapter = newAdapter();

        BigDecimal rate = adapter.getUsdKrwRate();

        assertThat(rate).isEqualByComparingTo(new BigDecimal("1370.50"));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/latest")));
    }

    @Test
    void KIS_환율_필드_없으면_폴백() {
        stubTokenIssue();
        stubFxKisWithoutRate();
        stubFallbackFx("1380.25");
        KisMarketDataAdapter adapter = newAdapter();

        BigDecimal rate = adapter.getUsdKrwRate();

        assertThat(rate).isEqualByComparingTo(new BigDecimal("1380.25"));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/latest")));
    }

    @Test
    void KIS_환율_비정상값이면_폴백() {
        stubTokenIssue();
        stubFxKisWithRate("0");
        stubFallbackFx("1395.00");
        KisMarketDataAdapter adapter = newAdapter();

        BigDecimal rate = adapter.getUsdKrwRate();

        assertThat(rate).isEqualByComparingTo(new BigDecimal("1395.00"));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/latest")));
    }

    @Test
    void 환율_캐시는_TTL_동안_재사용되고_초과시_갱신() {
        stubTokenIssue();
        stubFxKisWithRate("1370.50");
        KisMarketDataAdapter adapter = newAdapter();

        BigDecimal first = adapter.getUsdKrwRate();
        BigDecimal cached = adapter.getUsdKrwRate();

        assertThat(first).isEqualByComparingTo("1370.50");
        assertThat(cached).isEqualByComparingTo("1370.50");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail")));

        // TTL 6시간을 초과하도록 시계 점프 — SnapStart snapshot 의 fxCache 유효 윈도우와 일치.
        Instant later = Instant.parse("2026-04-28T00:00:00Z").plus(Duration.ofHours(6)).plusSeconds(1);
        clockHolder.set(Clock.fixed(later, ZoneOffset.UTC));
        wireMock.resetRequests();
        stubFxKisWithRate("1399.99");

        BigDecimal refreshed = adapter.getUsdKrwRate();

        assertThat(refreshed).isEqualByComparingTo("1399.99");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail")));
    }

    @Test
    void 시세_조회_가격필드_없으면_명확히_실패() {
        stubTokenIssue();
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "rt_cd": "0",
                                  "output": { "rsym": "DNASAAPL" }
                                }
                                """)));
        KisMarketDataAdapter adapter = newAdapter();

        try {
            adapter.getQuote("AAPL", Exchange.NAS);
            org.junit.jupiter.api.Assertions.fail("예외가 발생해야 한다");
        } catch (RuntimeException ex) {
            assertThat(ex).isInstanceOf(IllegalStateException.class);
            assertThat(ex.getMessage()).contains("가격 필드");
        }
    }

    @Test
    void 시세_조회_시_헤더_검증() {
        stubTokenIssue();
        stubQuote("AAPL", "NAS", "100.00");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withHeader("authorization", equalTo("Bearer ACCESS-1"))
                .withHeader("appkey", equalTo("test-key"))
                .withHeader("appsecret", equalTo("test-secret"))
                .withHeader("tr_id", equalTo("HHDFS76200200"))
                .withHeader("custtype", equalTo("P")));
    }

    private void setKstNow(LocalDateTime kst) {
        Instant instant = kst.atZone(ZoneId.of("Asia/Seoul")).toInstant();
        clockHolder.set(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void stubEmptyQuote(String exchange) {
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo(exchange))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "rt_cd": "0",
                                  "msg1": "조회된 데이터가 없습니다",
                                  "output": { "rsym": "" }
                                }
                                """)));
    }

    @Test
    void 주간장_시간엔_BAQ_코드로_조회한다() {
        stubTokenIssue();
        // KST 12:00 → 주간장 시간대
        setKstNow(LocalDateTime.of(2026, 4, 28, 12, 0));
        stubQuote("AAPL", "BAQ", "180.00");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.exchange()).isEqualTo(Exchange.NAS); // 도메인 enum 은 정규장 그대로 노출
        assertThat(quote.price().amount()).isEqualByComparingTo(new BigDecimal("180.00"));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ"))
                .withQueryParam("SYMB", equalTo("AAPL")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
    }

    @Test
    void 주간장_빈응답이면_정규장_코드로_fallback() {
        stubTokenIssue();
        setKstNow(LocalDateTime.of(2026, 4, 28, 12, 0));
        // BAQ 는 가격 필드 누락, NAS 는 정상 응답
        stubEmptyQuote("BAQ");
        stubQuote("AAPL", "NAS", "175.50");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.price().amount()).isEqualByComparingTo(new BigDecimal("175.50"));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
    }

    @Test
    void 주간장_정규장_둘다_빈응답이면_예외() {
        stubTokenIssue();
        setKstNow(LocalDateTime.of(2026, 4, 28, 12, 0));
        stubEmptyQuote("BAQ");
        stubEmptyQuote("NAS");
        KisMarketDataAdapter adapter = newAdapter();

        try {
            adapter.getQuote("AAPL", Exchange.NAS);
            org.junit.jupiter.api.Assertions.fail("예외가 발생해야 한다");
        } catch (RuntimeException ex) {
            assertThat(ex).isInstanceOf(IllegalStateException.class);
            assertThat(ex.getMessage()).contains("가격 필드");
        }
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
    }

    @Test
    void 주간장_시간_외엔_정규장_코드만_호출() {
        stubTokenIssue();
        // KST 18:00 → 주간장 종료 후
        setKstNow(LocalDateTime.of(2026, 4, 28, 18, 0));
        stubQuote("AAPL", "NAS", "175.50");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
    }

    @Test
    void 주간장_시작_경계_10시_정각엔_주간장_코드() {
        stubTokenIssue();
        setKstNow(LocalDateTime.of(2026, 4, 28, 10, 0, 0));
        stubQuote("AAPL", "BAQ", "180.00");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
    }

    @Test
    void 주간장_종료_경계_17시30분_정각엔_정규장_코드() {
        stubTokenIssue();
        setKstNow(LocalDateTime.of(2026, 4, 28, 17, 30, 0));
        stubQuote("AAPL", "NAS", "175.50");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
    }

    @Test
    void 주간장_시간이라도_토요일이면_정규장_코드() {
        stubTokenIssue();
        // 2026-05-02 는 토요일, KST 12:00
        setKstNow(LocalDateTime.of(2026, 5, 2, 12, 0));
        stubQuote("AAPL", "NAS", "175.50");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
    }

    @Test
    void 주간장_시간이라도_일요일이면_정규장_코드() {
        stubTokenIssue();
        // 2026-05-03 은 일요일, KST 12:00
        setKstNow(LocalDateTime.of(2026, 5, 3, 12, 0));
        stubQuote("AAPL", "NAS", "175.50");
        KisMarketDataAdapter adapter = newAdapter();

        adapter.getQuote("AAPL", Exchange.NAS);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("NAS")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAQ")));
    }

    /**
     * 등락률·52주 고저용 자유 응답 스텁. body 를 직접 받아 테스트마다 필드 조합을 자유롭게 구성.
     */
    private void stubQuoteRaw(String ticker, String exchange, String body) {
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo(exchange))
                .withQueryParam("SYMB", equalTo(ticker))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void 등락률_rate와_sign_2면_양수() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"175.50","base":"170.00","rate":"3.24","sign":"2"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.dailyChangePct()).isEqualByComparingTo(new BigDecimal("3.24"));
    }

    @Test
    void 등락률_rate와_sign_5면_음수() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"165.00","base":"170.00","rate":"2.94","sign":"5"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.dailyChangePct()).isEqualByComparingTo(new BigDecimal("-2.94"));
    }

    @Test
    void 등락률_rate와_sign_3이면_0() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"170.00","base":"170.00","rate":"0","sign":"3"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.dailyChangePct()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 등락률_rate만_있고_sign_누락이면_raw_그대로() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"165.00","base":"170.00","rate":"-2.94"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.dailyChangePct()).isEqualByComparingTo(new BigDecimal("-2.94"));
    }

    @Test
    void 등락률_rate_sign_없고_last_base만_있으면_폴백계산() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"175.50","base":"170.00"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        // (175.50 - 170.00) / 170.00 * 100 = 3.235.. → HALF_UP 2자리 = 3.24
        assertThat(quote.dailyChangePct()).isEqualByComparingTo(new BigDecimal("3.24"));
    }

    @Test
    void 등락률_rate_sign_base_모두_없으면_null() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"175.50"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.dailyChangePct()).isNull();
    }

    @Test
    void 주간_고저_둘다_정상이면_그대로_노출() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"175.50","h52p":"199.00","l52p":"120.00"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.weekHigh52()).isEqualByComparingTo(new BigDecimal("199.00"));
        assertThat(quote.weekLow52()).isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    void 주간_고저_한쪽만_있으면_둘다_null() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"175.50","h52p":"199.00"
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(quote.weekHigh52()).isNull();
        assertThat(quote.weekLow52()).isNull();
    }

    @Test
    void 보조필드_빈문자열은_null로_정규화_단_가격은_정상() {
        stubTokenIssue();
        stubQuoteRaw("AAPL", "NAS", """
                {"rt_cd":"0","output":{
                  "last":"175.50","base":"","rate":"","sign":"","h52p":"","l52p":""
                }}""");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("AAPL", Exchange.NAS);

        // 가격은 정상, 보조 필드는 모두 null — 빈 문자열·결측은 시세 자체를 실패시키지 않음
        assertThat(quote.price().amount()).isEqualByComparingTo(new BigDecimal("175.50"));
        assertThat(quote.dailyChangePct()).isNull();
        assertThat(quote.weekHigh52()).isNull();
        assertThat(quote.weekLow52()).isNull();
    }

    @Test
    void NYS_종목은_BAY_로_매핑된다() {
        stubTokenIssue();
        setKstNow(LocalDateTime.of(2026, 4, 28, 12, 0));
        stubQuote("GE", "BAY", "150.00");
        KisMarketDataAdapter adapter = newAdapter();

        Quote quote = adapter.getQuote("GE", Exchange.NYS);

        assertThat(quote.exchange()).isEqualTo(Exchange.NYS);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price-detail"))
                .withQueryParam("EXCD", equalTo("BAY")));
    }

}
