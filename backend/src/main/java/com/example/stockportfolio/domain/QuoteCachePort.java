package com.example.stockportfolio.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * KST 10분 슬롯 단위 시세 캐시. 어댑터 구현이 best-effort 가속기 역할이며 실패는 호출자가 폴스루 처리한다.
 *
 * 슬롯 라운딩(`asOf` → 10분 floor)은 어댑터 구현 내부 책임이다.
 */
public interface QuoteCachePort {

    Optional<Quote> find(String ticker, Exchange exchange, Instant asOf);

    void put(Quote quote, Instant asOf);
}
