package com.example.stockportfolio.adapter.marketdata.kis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class KisAccessTokenManager {

    private static final Logger log = LoggerFactory.getLogger(KisAccessTokenManager.class);
    private static final long REFRESH_MARGIN_SECONDS = 60L;

    private final RestClient restClient;
    private final String baseUrl;
    private final KisCredentialsProvider credentialsProvider;
    private final Clock clock;
    private final KisAccessTokenStore store;
    // Lambda 단일 인스턴스 환경이지만 동시 갱신 안전성을 명시적으로 보장한다.
    private final AtomicReference<CachedToken> cache = new AtomicReference<>();

    public KisAccessTokenManager(RestClient restClient,
                                 String baseUrl,
                                 KisCredentialsProvider credentialsProvider,
                                 Clock clock,
                                 KisAccessTokenStore store) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.credentialsProvider = credentialsProvider;
        this.clock = clock;
        this.store = store;
    }

    public String getAccessToken() {
        CachedToken current = cache.get();
        Instant now = clock.instant();
        if (isFresh(current, now)) {
            return current.token();
        }
        synchronized (this) {
            now = clock.instant();
            current = cache.get();
            if (isFresh(current, now)) {
                return current.token();
            }
            // 1) DDB 박제 토큰 재사용 시도 — 콜드 스타트마다 KIS 호출 회피.
            Optional<KisAccessTokenStore.StoredToken> stored = loadFromStore();
            if (stored.isPresent() && stored.get().expiresAt().isAfter(now.plusSeconds(REFRESH_MARGIN_SECONDS))) {
                CachedToken fromStore = new CachedToken(stored.get().accessToken(), stored.get().expiresAt());
                cache.set(fromStore);
                return fromStore.token();
            }
            // 2) DDB 도 비었거나 만료 — KIS 발급 + DDB 박제(best-effort) + in-memory 캐시.
            CachedToken refreshed = issueNew();
            persistToStore(new KisAccessTokenStore.StoredToken(refreshed.token(), refreshed.expiresAt()));
            cache.set(refreshed);
            return refreshed.token();
        }
    }

    private boolean isFresh(CachedToken token, Instant now) {
        return token != null && token.expiresAt().isAfter(now.plusSeconds(REFRESH_MARGIN_SECONDS));
    }

    private Optional<KisAccessTokenStore.StoredToken> loadFromStore() {
        try {
            return store.find();
        } catch (RuntimeException ex) {
            // best-effort: 저장소 장애 시 KIS 발급으로 폴스루.
            log.warn("KIS access token DDB find 실패 — KIS 발급으로 폴스루: {}", ex.toString());
            return Optional.empty();
        }
    }

    private void persistToStore(KisAccessTokenStore.StoredToken token) {
        try {
            store.save(token);
        } catch (RuntimeException ex) {
            // best-effort: 박제 실패해도 in-memory 캐시는 유효 → 호출자에게 전파하지 않음.
            log.warn("KIS access token DDB save 실패 — in-memory 캐시만 유지: {}", ex.toString());
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
            // 응답 본문 전체를 메시지에 박지 않는다 (잠재적 토큰/시크릿 누출 방지).
            // 디버깅엔 키 목록만 충분.
            Iterable<String> keys = response != null ? response.propertyNames() : java.util.List.of();
            throw new IllegalStateException("KIS 토큰 응답에 access_token 이 없습니다 (응답 키: " + keys + ")");
        }
        String token = response.get("access_token").asString();
        long expiresIn = response.has("expires_in") ? response.get("expires_in").asLong(86400L) : 86400L;
        Instant expiresAt = clock.instant().plusSeconds(expiresIn);
        return new CachedToken(token, expiresAt);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
