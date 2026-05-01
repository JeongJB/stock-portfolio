package com.example.stockportfolio.application;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.TickerMetaRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeResolverTest {

    // 2026-04-28 00:00:00Z = 2026-04-28 09:00 KST → KST 날짜 = 2026-04-28
    private static final Instant FIXED_NOW = Instant.parse("2026-04-28T00:00:00Z");

    private InMemoryMetaRepo repo;
    private CountingMarket market;
    private Clock clock;
    private ExchangeResolver resolver;

    @BeforeEach
    void setUp() {
        repo = new InMemoryMetaRepo();
        market = new CountingMarket();
        clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        resolver = new ExchangeResolver(market, repo, clock);
    }

    @Test
    void resolve_META_있고_카운터_미만이면_MarketData_미호출() {
        repo.save(new TickerMeta("AAPL", Exchange.NAS, FIXED_NOW.minusSeconds(60), 2));

        Exchange ex = resolver.resolve("AAPL");

        assertThat(ex).isEqualTo(Exchange.NAS);
        assertThat(market.calls).isEmpty();
    }

    @Test
    void resolve_META_없으면_NAS_탐색_성공시_즉시_저장하고_반환() {
        market.respond("GEV", Exchange.NAS, "100.00");

        Exchange ex = resolver.resolve("GEV");

        assertThat(ex).isEqualTo(Exchange.NAS);
        assertThat(market.calls).containsExactly(call("GEV", Exchange.NAS));
        TickerMeta meta = repo.find("GEV").orElseThrow();
        assertThat(meta.exchange()).isEqualTo(Exchange.NAS);
        assertThat(meta.consecutiveQuoteFailures()).isZero();
        assertThat(meta.lastVerifiedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void resolve_META_없고_NAS_실패_NYS_성공시_NYS로_저장() {
        market.respond("GEV", Exchange.NYS, "100.00");
        // NAS 응답 없음 → 자동 실패 (CountingMarket.getQuote 가 IllegalStateException)

        Exchange ex = resolver.resolve("GEV");

        assertThat(ex).isEqualTo(Exchange.NYS);
        assertThat(market.calls).containsExactly(
                call("GEV", Exchange.NAS),
                call("GEV", Exchange.NYS));
        TickerMeta meta = repo.find("GEV").orElseThrow();
        assertThat(meta.exchange()).isEqualTo(Exchange.NYS);
        assertThat(meta.consecutiveQuoteFailures()).isZero();
    }

    @Test
    void resolve_META_있고_카운터_임계_이상이면_재탐색하고_META_갱신() {
        // 기존 META: NAS 였는데 3 회 연속 실패. 이번에 NYS 로 재탐색 성공.
        repo.save(new TickerMeta("GEV", Exchange.NAS, FIXED_NOW.minusSeconds(86400), 3));
        market.respond("GEV", Exchange.NYS, "100.00");

        Exchange ex = resolver.resolve("GEV");

        assertThat(ex).isEqualTo(Exchange.NYS);
        TickerMeta meta = repo.find("GEV").orElseThrow();
        assertThat(meta.exchange()).isEqualTo(Exchange.NYS);
        assertThat(meta.consecutiveQuoteFailures()).isZero();
        assertThat(meta.lastVerifiedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void resolve_모든_거래소_실패시_마지막_예외_throw_META_변경없음() {
        // 응답 등록 없음 → 모든 호출 실패
        assertThatThrownBy(() -> resolver.resolve("XXX"))
                .isInstanceOf(RuntimeException.class);

        assertThat(market.calls).containsExactly(
                call("XXX", Exchange.NAS),
                call("XXX", Exchange.NYS),
                call("XXX", Exchange.AMS));
        assertThat(repo.find("XXX")).isEmpty();
    }

    @Test
    void resolve_재탐색_모두_실패시_기존_META는_그대로_유지() {
        TickerMeta original = new TickerMeta("XXX", Exchange.NAS, FIXED_NOW.minusSeconds(60), 5);
        repo.save(original);

        assertThatThrownBy(() -> resolver.resolve("XXX"))
                .isInstanceOf(RuntimeException.class);

        // META 는 resolve 가 변경하지 않음. 카운터 누적은 별도 onQuoteFailure 호출의 책임.
        assertThat(repo.find("XXX")).contains(original);
    }

    @Test
    void onQuoteSuccess_같은_KST_날짜이고_카운터_0이면_PUT_skip() {
        repo.save(new TickerMeta("AAPL", Exchange.NAS, FIXED_NOW, 0));
        repo.clearWriteCount();

        // 같은 시각으로 호출 — KST 날짜 동일
        resolver.onQuoteSuccess("AAPL", FIXED_NOW);

        assertThat(repo.writeCount).isZero();
    }

    @Test
    void onQuoteSuccess_같은_날짜라도_카운터_0_아니면_PUT() {
        repo.save(new TickerMeta("AAPL", Exchange.NAS, FIXED_NOW, 1));
        repo.clearWriteCount();

        resolver.onQuoteSuccess("AAPL", FIXED_NOW);

        assertThat(repo.writeCount).isEqualTo(1);
        TickerMeta after = repo.find("AAPL").orElseThrow();
        assertThat(after.consecutiveQuoteFailures()).isZero();
    }

    @Test
    void onQuoteSuccess_다른_KST_날짜면_PUT() {
        repo.save(new TickerMeta("AAPL", Exchange.NAS, FIXED_NOW, 0));
        repo.clearWriteCount();

        Instant nextKstDay = FIXED_NOW.plus(Duration.ofDays(1));
        resolver.onQuoteSuccess("AAPL", nextKstDay);

        assertThat(repo.writeCount).isEqualTo(1);
        TickerMeta after = repo.find("AAPL").orElseThrow();
        assertThat(after.lastVerifiedAt()).isEqualTo(nextKstDay);
    }

    @Test
    void onQuoteFailure는_META가_있을때_카운터를_1_증가시킨다() {
        repo.save(new TickerMeta("AAPL", Exchange.NAS, FIXED_NOW.minusSeconds(60), 1));

        resolver.onQuoteFailure("AAPL");

        TickerMeta after = repo.find("AAPL").orElseThrow();
        assertThat(after.consecutiveQuoteFailures()).isEqualTo(2);
    }

    @Test
    void onQuoteFailure는_META가_없으면_무시() {
        repo.clearWriteCount();
        resolver.onQuoteFailure("UNKNOWN");
        assertThat(repo.writeCount).isZero();
    }

    private static Call call(String ticker, Exchange exchange) {
        return new Call(ticker, exchange);
    }

    private record Call(String ticker, Exchange exchange) {}

    /** 응답 등록한 (ticker, exchange) 만 정상 응답하고, 그 외는 IllegalStateException 으로 실패. */
    private static final class CountingMarket implements MarketDataPort {
        final List<Call> calls = new java.util.ArrayList<>();
        private final Map<String, BigDecimal> registered = new HashMap<>();

        void respond(String ticker, Exchange exchange, String price) {
            registered.put(key(ticker, exchange), new BigDecimal(price));
        }

        @Override
        public Quote getQuote(String ticker, Exchange exchange) {
            calls.add(new Call(ticker, exchange));
            BigDecimal price = registered.get(key(ticker, exchange));
            if (price == null) {
                throw new IllegalStateException(
                        "stub 시세 미등록 (" + ticker + ", " + exchange + ")");
            }
            return new Quote(ticker, exchange, Money.of(price, Currency.USD), Instant.EPOCH);
        }

        @Override
        public BigDecimal getUsdKrwRate() {
            return new BigDecimal("1400");
        }

        private static String key(String ticker, Exchange exchange) {
            return ticker + "@" + exchange.name();
        }
    }

    /** 단순 ConcurrentHashMap fake + write 카운터. */
    private static final class InMemoryMetaRepo implements TickerMetaRepository {
        private final Map<String, TickerMeta> store = new HashMap<>();
        private final Set<String> seenWrites = new HashSet<>();
        int writeCount = 0;

        @Override public Optional<TickerMeta> find(String ticker) {
            return Optional.ofNullable(store.get(ticker));
        }
        @Override public void save(TickerMeta meta) {
            store.put(meta.ticker(), meta);
            writeCount++;
            seenWrites.add(meta.ticker());
        }
        void clearWriteCount() {
            writeCount = 0;
            seenWrites.clear();
        }
    }
}
