package com.example.stockportfolio.adapter.marketdata.kis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * USD/KRW 환율 영속 저장소. 만료 여부 판단은 호출자({@link KisMarketDataAdapter}) 책임.
 *
 * <p>존재 이유: in-memory {@code fxCache} 만으로는 SnapStart deploy 시 init phase 가 2회 실행되는
 * 경우 두 번째 init 의 KIS 호출이 EGW00133 ("1분당 1회") 로 거부되고 fallback 도 실패해 fxCache 가
 * 빈 상태로 snapshot 에 박힌다. DDB 에 박제하면 두 번째 init 이 DDB hit 으로 즉시 캐시 채움 가능 +
 * 사용자 cold first invoke 도 DDB hit (~30-100ms) 으로 항상 빠름.
 *
 * <p>어댑터 구현체는 DynamoDB 등 외부 저장소에 의존하므로 도메인 패키지가 아닌 KIS 어댑터 패키지 안에 둔다.
 */
public interface FxRateStore {

    Optional<StoredRate> find();

    void save(StoredRate rate);

    record StoredRate(BigDecimal rate, Instant expiresAt) {}
}
