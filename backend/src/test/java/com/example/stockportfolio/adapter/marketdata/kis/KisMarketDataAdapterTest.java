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
        KisAccessTokenManager tokenManager = new KisAccessTokenManager(restClient, baseUrl, credentialsProvider, proxyClock);
        KisHttpClient kisHttpClient = new KisHttpClient(restClient, baseUrl, tokenManager, credentialsProvider);
        return new KisMarketDataAdapter(
                kisHttpClient,
                restClient,
                baseUrl,
                "AAPL",
                Exchange.NAS,
                proxyClock);
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
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price"))
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
                                    "t_xrat": "%s"
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
                .withQueryParam("base", equalTo("USD"))
                .withQueryParam("symbols", equalTo("KRW"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "base": "USD",
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
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price")));
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
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price")));
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

        // TTL 1시간을 초과하도록 시계 점프
        Instant later = Instant.parse("2026-04-28T00:00:00Z").plus(Duration.ofHours(1)).plusSeconds(1);
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
        wireMock.stubFor(get(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price"))
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

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/uapi/overseas-price/v1/quotations/price"))
                .withHeader("authorization", equalTo("Bearer ACCESS-1"))
                .withHeader("appkey", equalTo("test-key"))
                .withHeader("appsecret", equalTo("test-secret"))
                .withHeader("tr_id", equalTo("HHDFS00000300"))
                .withHeader("custtype", equalTo("P")));
    }

}
