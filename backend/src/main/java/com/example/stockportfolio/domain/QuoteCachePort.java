package com.example.stockportfolio.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * KST 기준 일자별 시세 캐시. 어댑터 구현이 best-effort 가속기 역할이며 실패는 호출자가 폴스루 처리한다.
 */
public interface QuoteCachePort {

    Optional<Quote> find(String ticker, Exchange exchange, LocalDate kstDate);

    void put(Quote quote, LocalDate kstDate);
}
