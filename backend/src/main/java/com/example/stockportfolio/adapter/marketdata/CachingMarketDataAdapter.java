package com.example.stockportfolio.adapter.marketdata;

import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.QuoteCachePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 시세 조회를 KST 10분 슬롯 단위 캐시로 감싸는 데코레이터.
 *
 * 같은 10분 KST 슬롯 안에 같은 ticker/exchange 조회는 한 번만 KIS 를 호출한다. 캐시 자체 장애는
 * best-effort 로 흡수하여 응답을 깨뜨리지 않는다 (WARN 로그 후 KIS 폴스루).
 *
 * 환율은 위임 어댑터(`KisMarketDataAdapter`)의 in-memory TTL 캐시를 그대로 사용하므로
 * 본 데코레이터는 손대지 않는다.
 */
public final class CachingMarketDataAdapter implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(CachingMarketDataAdapter.class);

    private final MarketDataPort delegate;
    private final QuoteCachePort cache;
    private final Clock clock;

    public CachingMarketDataAdapter(MarketDataPort delegate, QuoteCachePort cache, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Quote getQuote(String ticker, Exchange exchange) {
        Instant now = clock.instant();

        Optional<Quote> cached = findSafely(ticker, exchange, now);
        if (cached.isPresent()) {
            return cached.get();
        }

        Quote fresh = delegate.getQuote(ticker, exchange);
        putSafely(fresh, now);
        return fresh;
    }

    @Override
    public BigDecimal getUsdKrwRate() {
        return delegate.getUsdKrwRate();
    }

    private Optional<Quote> findSafely(String ticker, Exchange exchange, Instant asOf) {
        try {
            return cache.find(ticker, exchange, asOf);
        } catch (RuntimeException ex) {
            log.warn("시세 캐시 조회 실패 ticker={} asOf={} → KIS 폴스루: {}",
                    ticker, asOf, ex.toString());
            return Optional.empty();
        }
    }

    private void putSafely(Quote quote, Instant asOf) {
        try {
            cache.put(quote, asOf);
        } catch (RuntimeException ex) {
            log.warn("시세 캐시 저장 실패 ticker={} asOf={} → 무시하고 진행: {}",
                    quote.ticker(), asOf, ex.toString());
        }
    }
}
