package com.example.stockportfolio.adapter.marketdata;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.QuoteCachePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachingMarketDataAdapterTest {

    // 2026-04-28 09:00 KST = 2026-04-28 00:00:00Z 기준 시각
    private static final Instant FIXED_NOW = Instant.parse("2026-04-28T00:00:00Z");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private FakeQuoteCache cache;
    private CountingDelegate delegate;
    private Clock clock;
    private CachingMarketDataAdapter adapter;

    @BeforeEach
    void setUp() {
        cache = new FakeQuoteCache();
        delegate = new CountingDelegate();
        clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        adapter = new CachingMarketDataAdapter(delegate, cache, clock);
    }

    @Test
    void 캐시_miss이면_위임_호출_후_캐시에_저장한다() {
        Quote q = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(q.ticker()).isEqualTo("AAPL");
        assertThat(q.price().amount()).isEqualByComparingTo("175.50");
        assertThat(delegate.callCount.get()).isEqualTo(1);
        assertThat(cache.store).hasSize(1);

        // KST 오늘 = 2026-04-28 (UTC 기준 00:00 → KST 09:00)
        LocalDate kstToday = LocalDate.of(2026, 4, 28);
        Quote stored = cache.store.get(cacheKey("AAPL", kstToday));
        assertThat(stored).isNotNull();
        assertThat(stored.price().amount()).isEqualByComparingTo("175.50");
    }

    @Test
    void 캐시_hit이면_위임을_호출하지_않고_저장된_값을_반환한다() {
        // 사전 적재
        LocalDate kstToday = LocalDate.of(2026, 4, 28);
        Quote pre = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("123.45"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        cache.store.put(cacheKey("AAPL", kstToday), pre);

        Quote q = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(q.price().amount()).isEqualByComparingTo("123.45");
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    void 캐시_조회_예외시_위임으로_폴스루하고_응답은_정상() {
        cache.failOnFind = true;

        Quote q = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(q.price().amount()).isEqualByComparingTo("175.50");
        assertThat(delegate.callCount.get()).isEqualTo(1);
        // 저장은 시도하지만 실패하지 않음 — 정상 putItem
        // (find만 실패하도록 설정했음)
    }

    @Test
    void 캐시_저장_예외시에도_응답은_정상() {
        cache.failOnPut = true;

        Quote q = adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(q.price().amount()).isEqualByComparingTo("175.50");
        assertThat(delegate.callCount.get()).isEqualTo(1);
    }

    @Test
    void 환율은_위임에_그대로_위임한다() {
        BigDecimal r = adapter.getUsdKrwRate();
        assertThat(r).isEqualByComparingTo("1370.50");
        assertThat(delegate.fxCallCount.get()).isEqualTo(1);
    }

    private static String cacheKey(String ticker, LocalDate date) {
        return ticker + "|" + date;
    }

    /**
     * KIS 호출 위임 모킹. 같은 ticker/exchange 에 대해 호출 시마다 동일 가격을 반환.
     */
    private static final class CountingDelegate implements MarketDataPort {
        final AtomicInteger callCount = new AtomicInteger();
        final AtomicInteger fxCallCount = new AtomicInteger();

        @Override
        public Quote getQuote(String ticker, Exchange exchange) {
            callCount.incrementAndGet();
            return new Quote(ticker, exchange,
                    new Money(new BigDecimal("175.50"), Currency.USD),
                    Instant.parse("2026-04-27T20:00:00Z"));
        }

        @Override
        public BigDecimal getUsdKrwRate() {
            fxCallCount.incrementAndGet();
            return new BigDecimal("1370.50");
        }
    }

    private static final class FakeQuoteCache implements QuoteCachePort {
        final Map<String, Quote> store = new HashMap<>();
        boolean failOnFind = false;
        boolean failOnPut = false;

        @Override
        public Optional<Quote> find(String ticker, Exchange exchange, LocalDate kstDate) {
            if (failOnFind) {
                throw new RuntimeException("simulated find failure");
            }
            Quote q = store.get(cacheKey(ticker, kstDate));
            if (q == null || q.exchange() != exchange) {
                return Optional.empty();
            }
            return Optional.of(q);
        }

        @Override
        public void put(Quote quote, LocalDate kstDate) {
            if (failOnPut) {
                throw new RuntimeException("simulated put failure");
            }
            store.put(cacheKey(quote.ticker(), kstDate), quote);
        }
    }
}
