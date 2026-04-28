package com.example.stockportfolio.adapter.marketdata.kis;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class KisAccessTokenManager {

    private static final long REFRESH_MARGIN_SECONDS = 60L;

    private final RestClient restClient;
    private final String baseUrl;
    private final KisCredentialsProvider credentialsProvider;
    private final Clock clock;
    // Lambda 단일 인스턴스 환경이지만 동시 갱신 안전성을 명시적으로 보장한다.
    private final AtomicReference<CachedToken> cache = new AtomicReference<>();

    public KisAccessTokenManager(RestClient restClient,
                                 String baseUrl,
                                 KisCredentialsProvider credentialsProvider,
                                 Clock clock) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.credentialsProvider = credentialsProvider;
        this.clock = clock;
    }

    public String getAccessToken() {
        CachedToken current = cache.get();
        Instant now = clock.instant();
        if (current != null && current.expiresAt().isAfter(now.plusSeconds(REFRESH_MARGIN_SECONDS))) {
            return current.token();
        }
        synchronized (this) {
            current = cache.get();
            if (current != null && current.expiresAt().isAfter(now.plusSeconds(REFRESH_MARGIN_SECONDS))) {
                return current.token();
            }
            CachedToken refreshed = issueNew();
            cache.set(refreshed);
            return refreshed.token();
        }
    }

    private CachedToken issueNew() {
        KisCredentialsProvider.KisCredentials creds = credentialsProvider.get();
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", creds.appKey(),
                "appsecret", creds.appSecret()
        );
        JsonNode response = restClient.post()
                .uri(baseUrl + "/oauth2/tokenP")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.has("access_token")) {
            throw new IllegalStateException("KIS 토큰 응답에 access_token 이 없습니다: " + response);
        }
        String token = response.get("access_token").asString();
        long expiresIn = response.has("expires_in") ? response.get("expires_in").asLong(86400L) : 86400L;
        Instant expiresAt = clock.instant().plusSeconds(expiresIn);
        return new CachedToken(token, expiresAt);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
