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
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

        // 2026-04-28 09:00 KST 슬롯에 적재됐는지 확인 (10분 floor → 09:00)
        String slotKey = slotKey("AAPL", FIXED_NOW);
        Quote stored = cache.store.get(slotKey);
        assertThat(stored).isNotNull();
        assertThat(stored.price().amount()).isEqualByComparingTo("175.50");
    }

    @Test
    void 캐시_hit이면_위임을_호출하지_않고_저장된_값을_반환한다() {
        // 사전 적재 (FIXED_NOW 와 같은 슬롯)
        Quote pre = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("123.45"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        cache.store.put(slotKey("AAPL", FIXED_NOW), pre);

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

    /**
     * 같은 10분 슬롯 안의 두 시각은 같은 슬롯 키로 정규화되므로 두 번째 호출은 캐시 hit.
     */
    @Test
    void 같은_10분_슬롯_안의_두_시각은_캐시_hit() {
        // 09:00 KST → put
        Clock c1 = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        adapter = new CachingMarketDataAdapter(delegate, cache, c1);
        adapter.getQuote("AAPL", Exchange.NAS);

        // 09:09 KST → 같은 슬롯이므로 hit (위임 호출 추가 없음)
        Clock c2 = Clock.fixed(FIXED_NOW.plusSeconds(9 * 60 + 59), ZoneId.of("UTC"));
        adapter = new CachingMarketDataAdapter(delegate, cache, c2);
        adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(delegate.callCount.get()).isEqualTo(1);
    }

    /**
     * 슬롯 경계를 넘는 두 시각은 키가 다르므로 두 번 다 위임 호출.
     */
    @Test
    void 슬롯_경계를_넘으면_캐시_miss로_위임을_재호출() {
        // 09:00 KST → put
        Clock c1 = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        adapter = new CachingMarketDataAdapter(delegate, cache, c1);
        adapter.getQuote("AAPL", Exchange.NAS);

        // 09:10 KST (정확히 다음 슬롯) → miss
        Clock c2 = Clock.fixed(FIXED_NOW.plusSeconds(10 * 60), ZoneId.of("UTC"));
        adapter = new CachingMarketDataAdapter(delegate, cache, c2);
        adapter.getQuote("AAPL", Exchange.NAS);

        assertThat(delegate.callCount.get()).isEqualTo(2);
        assertThat(cache.store).hasSize(2);
    }

    /**
     * 테스트 더미용 슬롯 키. 어댑터의 슬롯 라운딩과 동일 규칙(10분 floor + KST yyyyMMddHHmm).
     */
    private static String slotKey(String ticker, Instant asOf) {
        ZonedDateTime kst = asOf.atZone(KST);
        int floored = (kst.getMinute() / 10) * 10;
        ZonedDateTime slot = kst.withMinute(floored).withSecond(0).withNano(0);
        return ticker + "|" + slot.toLocalDateTime();
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
        public Optional<Quote> find(String ticker, Exchange exchange, Instant asOf) {
            if (failOnFind) {
                throw new RuntimeException("simulated find failure");
            }
            Quote q = store.get(slotKey(ticker, asOf));
            if (q == null || q.exchange() != exchange) {
                return Optional.empty();
            }
            return Optional.of(q);
        }

        @Override
        public void put(Quote quote, Instant asOf) {
            if (failOnPut) {
                throw new RuntimeException("simulated put failure");
            }
            store.put(slotKey(quote.ticker(), asOf), quote);
        }
    }
}
