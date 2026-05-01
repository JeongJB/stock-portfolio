package com.example.stockportfolio.adapter.marketdata.kis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3-layer 캐시(in-memory → DDB → KIS) 동작 단위 검증.
 */
class KisAccessTokenManagerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-28T00:00:00Z");

    private WireMockServer wireMock;
    private String baseUrl;
    private RestClient restClient;
    private KisCredentialsProvider credentialsProvider;
    private Clock clock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        restClient = RestClient.builder().requestFactory(factory).build();
        credentialsProvider = () -> new KisCredentialsProvider.KisCredentials("test-key", "test-secret");
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private void stubTokenIssue(String token, long expiresIn) {
        wireMock.stubFor(post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "%s",
                                  "token_type": "Bearer",
                                  "expires_in": %d
                                }
                                """.formatted(token, expiresIn))));
    }

    @Test
    void in_memory_hit이면_DDB와_KIS_둘다_미호출() {
        InMemoryKisAccessTokenStore store = new InMemoryKisAccessTokenStore();
        stubTokenIssue("FRESH", 86400);
        KisAccessTokenManager manager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, clock, store);

        // 첫 호출 — DDB miss + KIS 발급
        String first = manager.getAccessToken();
        assertThat(first).isEqualTo("FRESH");
        int findCountAfterFirst = store.findCount;
        int saveCountAfterFirst = store.saveCount;

        // 두 번째 호출 — in-memory hit
        String second = manager.getAccessToken();
        assertThat(second).isEqualTo("FRESH");
        assertThat(store.findCount).isEqualTo(findCountAfterFirst);
        assertThat(store.saveCount).isEqualTo(saveCountAfterFirst);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
    }

    @Test
    void in_memory_miss_DDB_hit이면_KIS_미호출_in_memory_채워짐() {
        Instant futureExpiry = FIXED_NOW.plusSeconds(3600);
        InMemoryKisAccessTokenStore store = new InMemoryKisAccessTokenStore(
                new KisAccessTokenStore.StoredToken("FROM-DDB", futureExpiry));
        KisAccessTokenManager manager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, clock, store);

        String token = manager.getAccessToken();

        assertThat(token).isEqualTo("FROM-DDB");
        assertThat(store.findCount).isEqualTo(1);
        assertThat(store.saveCount).isZero();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/oauth2/tokenP")));

        // 후속 호출은 in-memory hit
        manager.getAccessToken();
        assertThat(store.findCount).isEqualTo(1);
    }

    @Test
    void in_memory_miss_DDB_miss이면_KIS_호출_DDB_save_in_memory_채워짐() {
        InMemoryKisAccessTokenStore store = new InMemoryKisAccessTokenStore();
        stubTokenIssue("NEWLY-ISSUED", 86400);
        KisAccessTokenManager manager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, clock, store);

        String token = manager.getAccessToken();

        assertThat(token).isEqualTo("NEWLY-ISSUED");
        assertThat(store.findCount).isEqualTo(1);
        assertThat(store.saveCount).isEqualTo(1);
        assertThat(store.peek()).isNotNull();
        assertThat(store.peek().accessToken()).isEqualTo("NEWLY-ISSUED");
        // expiresIn 86400 → expiresAt 정확히 +86400s
        assertThat(store.peek().expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(86400));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
    }

    @Test
    void in_memory_miss_DDB_hit_but_만료이면_KIS_호출() {
        // DDB 의 토큰이 REFRESH_MARGIN(60s) 이내로 임박 → 폐기 후 KIS 재발급
        Instant nearExpiry = FIXED_NOW.plusSeconds(30);
        InMemoryKisAccessTokenStore store = new InMemoryKisAccessTokenStore(
                new KisAccessTokenStore.StoredToken("STALE", nearExpiry));
        stubTokenIssue("REFRESHED", 86400);
        KisAccessTokenManager manager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, clock, store);

        String token = manager.getAccessToken();

        assertThat(token).isEqualTo("REFRESHED");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
        // 새 토큰이 DDB 에 박제됨
        assertThat(store.peek().accessToken()).isEqualTo("REFRESHED");
    }

    @Test
    void DDB_find_예외시_KIS_폴스루() {
        InMemoryKisAccessTokenStore store = new InMemoryKisAccessTokenStore();
        store.failOnFind = true;
        stubTokenIssue("KIS-FALLBACK", 86400);
        KisAccessTokenManager manager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, clock, store);

        String token = manager.getAccessToken();

        assertThat(token).isEqualTo("KIS-FALLBACK");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
        // save 는 정상 시도 (find 만 실패하도록 했음) → save count 1
        assertThat(store.saveCount).isEqualTo(1);
    }

    @Test
    void DDB_save_예외시에도_정상_반환_in_memory_채워짐() {
        InMemoryKisAccessTokenStore store = new InMemoryKisAccessTokenStore();
        store.failOnSave = true;
        stubTokenIssue("KIS-OK", 86400);
        KisAccessTokenManager manager = new KisAccessTokenManager(
                restClient, baseUrl, credentialsProvider, clock, store);

        String token = manager.getAccessToken();

        assertThat(token).isEqualTo("KIS-OK");
        // 두 번째 호출은 in-memory hit (save 실패해도 in-memory 는 유효)
        String second = manager.getAccessToken();
        assertThat(second).isEqualTo("KIS-OK");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
    }
}
