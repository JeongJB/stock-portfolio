package com.example.stockportfolio.domain;

import java.util.Optional;

/**
 * 종목 마스터 영속 포트. cron 재검증을 두지 않는 설계라 findAll 은 일부러 노출하지 않는다.
 */
public interface TickerMetaRepository {

    Optional<TickerMeta> find(String ticker);

    /** upsert. 같은 ticker 라면 덮어쓴다. */
    void save(TickerMeta meta);
}
