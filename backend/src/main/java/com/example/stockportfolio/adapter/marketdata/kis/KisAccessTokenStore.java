package com.example.stockportfolio.adapter.marketdata.kis;

import java.time.Instant;
import java.util.Optional;

/**
 * KIS access token 영속 저장소. 만료 여부 판단은 호출자({@link KisAccessTokenManager}) 책임.
 *
 * 어댑터 구현체는 DynamoDB 등 외부 저장소에 의존하므로 도메인 패키지가 아닌
 * KIS 어댑터 패키지 안에 둔다.
 */
public interface KisAccessTokenStore {

    Optional<StoredToken> find();

    void save(StoredToken token);

    record StoredToken(String accessToken, Instant expiresAt) {}
}
