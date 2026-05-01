package com.example.stockportfolio.application;

import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.PositionView;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quantity;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.TickerMetaRepository;
import com.example.stockportfolio.domain.Trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioApplicationServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-04-28T00:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("정상: 포지션 + 현금 → 평가액·KRW·weight를 정확히 계산한다")
    void view_normalCase_computesValuationAndWeights() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("500", Currency.USD),
                Money.of("1500", Currency.USD),
                Money.zero(Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        // 평가액 USD = 200*10 = 2000, 분모 = 2000 + 500 = 2500
        assertEquals(new BigDecimal("2000.0000"), view.totalMarketValueUsd());
        assertEquals(new BigDecimal("1000.0000"), view.totalCostBasisUsd());
        // unrealized = 2000 - 1000 = 1000
        assertEquals(new BigDecimal("1000.0000"), view.totalUnrealizedPnlUsd());
        // KRW = USD * 1400
        assertEquals(new BigDecimal("2800000.0000"), view.totalMarketValueKrw());
        assertEquals(new BigDecimal("1400000.0000"), view.totalCostBasisKrw());
        assertEquals(new BigDecimal("700000.0000"), view.cashKrw());

        PositionView aapl = view.positions().get(0);
        // KIS 어댑터에서 Money.of(price, USD)로 감싸므로 scale 4가 적용된 표현으로 흘러나온다
        assertEquals(new BigDecimal("200.0000"), aapl.lastPriceUsd());
        assertEquals(new BigDecimal("280000.0000"), aapl.lastPriceKrw());
        assertEquals(new BigDecimal("2000.0000"), aapl.marketValueUsd());
        assertEquals(new BigDecimal("1000.0000"), aapl.unrealizedPnlUsd());
        assertEquals(new BigDecimal("1400000.0000"), aapl.unrealizedPnlKrw());

        // weight: 2000/2500 = 0.8, cashWeight: 500/2500 = 0.2, 합 = 1.0
        BigDecimal sum = aapl.weight().add(view.cashWeight());
        assertEquals(0, sum.compareTo(BigDecimal.ONE), "weight 합은 1.0이어야 한다 (실제: " + sum + ")");
    }

    @Test
    @DisplayName("실패 격리: 한 종목 시세 실패 시 해당 종목만 가격·평가액·weight를 null로 응답하고 200 유지")
    void view_oneTickerFails_isolatedAsNullsAndOthersOk() {
        Map<String, Position> positions = new HashMap<>();
        positions.put("AAPL", new Position("AAPL",
                Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD)));
        positions.put("BAD", new Position("BAD",
                Quantity.of("5"), Money.of("50", Currency.USD), Money.zero(Currency.USD)));

        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(positions,
                Money.of("100", Currency.USD),
                Money.of("100", Currency.USD),
                Money.zero(Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");
        market.fail("BAD");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        // 응답은 200 (예외 비전파), 시세 실패 종목은 가격 필드 null
        PositionView aapl = view.positions().stream()
                .filter(p -> p.ticker().equals("AAPL")).findFirst().orElseThrow();
        PositionView bad = view.positions().stream()
                .filter(p -> p.ticker().equals("BAD")).findFirst().orElseThrow();

        assertNotNull(aapl.lastPriceUsd());
        assertNotNull(aapl.weight());
        assertNotNull(aapl.unrealizedPnlUsd());

        assertNull(bad.lastPriceUsd());
        assertNull(bad.lastPriceKrw());
        assertNull(bad.marketValueUsd());
        assertNull(bad.marketValueKrw());
        assertNull(bad.weight());
        assertNull(bad.unrealizedPnlUsd());
        assertNull(bad.unrealizedPnlKrw());
        // 기본 필드는 정상
        assertEquals("BAD", bad.ticker());
        assertEquals(new BigDecimal("5.000000"), bad.qty());
        assertEquals(new BigDecimal("50.0000"), bad.avgCostUsd());

        // 합계는 시세 가용 종목만 — AAPL의 2000만 포함
        assertEquals(new BigDecimal("2000.0000"), view.totalMarketValueUsd());

        // weight 합 = AAPL.weight + cashWeight = 1.0 (BAD 제외)
        BigDecimal sum = aapl.weight().add(view.cashWeight());
        assertEquals(0, sum.compareTo(BigDecimal.ONE),
                "시세 실패 종목을 제외한 weight 합은 1.0 (실제: " + sum + ")");
    }

    @Test
    @DisplayName("빈 포트폴리오(현금만): cashWeight = 1.0, totalMarketValueUsd = 0")
    void view_cashOnly_cashWeightOne() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(Map.of(),
                Money.of("1000", Currency.USD),
                Money.of("1000", Currency.USD),
                Money.zero(Currency.USD)));

        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));
        PortfolioView view = service.view();

        assertEquals(0, BigDecimal.ZERO.compareTo(view.totalMarketValueUsd()));
        assertEquals(0, view.cashWeight().compareTo(BigDecimal.ONE),
                "현금만 있는 경우 cashWeight=1.0 (실제: " + view.cashWeight() + ")");
        assertTrue(view.positions().isEmpty());
    }

    @Test
    @DisplayName("완전히 빈 포트폴리오: 분모 0 → cashWeight = 0")
    void view_completelyEmpty_cashWeightZero() {
        FakeRepository repo = new FakeRepository();
        repo.set(new Portfolio());

        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));
        PortfolioView view = service.view();

        assertEquals(0, BigDecimal.ZERO.compareTo(view.cashWeight()));
        assertEquals(0, BigDecimal.ZERO.compareTo(view.totalMarketValueUsd()));
        assertEquals(0, BigDecimal.ZERO.compareTo(view.cashUsd()));
    }

    @Test
    @DisplayName("takeSnapshot: 현재 view() 결과를 박제하고 KST 기준 오늘 날짜 슬롯에 저장한다")
    void takeSnapshot_storesCurrentViewUnderKstToday() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("500", Currency.USD),
                Money.of("1500", Currency.USD),
                Money.zero(Currency.USD)));
        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");

        PortfolioApplicationService service = newService(repo, market);
        SnapshotView snapshot = service.takeSnapshot();

        // 2026-04-28T00:00:00Z = 2026-04-28T09:00+09:00 → KST 오늘 = 2026-04-28
        assertEquals(LocalDate.parse("2026-04-28"), snapshot.date());
        assertEquals(new BigDecimal("1400"), snapshot.usdKrwRate());
        assertEquals(new BigDecimal("2000.0000"), snapshot.totalMarketValueUsd());
        assertEquals(new BigDecimal("2800000.0000"), snapshot.totalMarketValueKrw());
        assertEquals(new BigDecimal("500.0000"), snapshot.cashUsd());
        assertEquals(new BigDecimal("1500.0000"), snapshot.principalUsd());
        assertEquals(1, snapshot.positions().size());
        assertEquals("AAPL", snapshot.positions().get(0).ticker());

        // 저장됐는지 확인
        assertEquals(1, repo.snapshots.size());
        assertEquals(snapshot, repo.snapshots.get(LocalDate.parse("2026-04-28")));
    }

    @Test
    @DisplayName("takeSnapshot: 같은 날짜 재호출 시 마지막 호출 결과로 덮어쓴다")
    void takeSnapshot_sameDayOverwrites() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(Map.of(),
                Money.of("100", Currency.USD),
                Money.of("100", Currency.USD),
                Money.zero(Currency.USD)));
        StubMarketData market = new StubMarketData(new BigDecimal("1400"));

        PortfolioApplicationService service = newService(repo, market);
        service.takeSnapshot();

        // 추가 입금으로 view 변경
        repo.set(buildPortfolio(Map.of(),
                Money.of("999", Currency.USD),
                Money.of("999", Currency.USD),
                Money.zero(Currency.USD)));
        SnapshotView second = service.takeSnapshot();

        assertEquals(1, repo.snapshots.size(), "같은 날짜는 한 슬롯만 남아야 한다");
        assertEquals(new BigDecimal("999.0000"), second.cashUsd());
        assertEquals(second, repo.snapshots.get(LocalDate.parse("2026-04-28")));
    }

    @Test
    @DisplayName("takeSnapshot: 빈 포트폴리오에서도 0/빈 positions로 박제한다")
    void takeSnapshot_emptyPortfolio() {
        FakeRepository repo = new FakeRepository();
        repo.set(new Portfolio());
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        SnapshotView snapshot = service.takeSnapshot();

        assertEquals(new BigDecimal("0.0000"), snapshot.cashUsd());
        assertEquals(new BigDecimal("0.0000"), snapshot.principalUsd());
        assertEquals(new BigDecimal("0.0000"), snapshot.totalMarketValueUsd());
        assertTrue(snapshot.positions().isEmpty());
    }

    @Test
    @DisplayName("listSnapshots: from/to 모두 null이면 KST 기준 today-90 ~ today 윈도를 사용한다")
    void listSnapshots_defaultWindowIsLast90Days() {
        FakeRepository repo = new FakeRepository();
        // FIXED = 2026-04-28T00:00:00Z → KST today = 2026-04-28, today-90 = 2026-01-28
        repo.snapshots.put(LocalDate.parse("2026-01-27"), stubSnapshot("2026-01-27")); // 윈도 밖
        repo.snapshots.put(LocalDate.parse("2026-01-28"), stubSnapshot("2026-01-28")); // 경계 inclusive
        repo.snapshots.put(LocalDate.parse("2026-04-28"), stubSnapshot("2026-04-28")); // 오늘 inclusive
        repo.snapshots.put(LocalDate.parse("2026-04-29"), stubSnapshot("2026-04-29")); // 미래(윈도 밖)

        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));
        List<SnapshotView> result = service.listSnapshots(null, null);

        assertEquals(2, result.size());
        assertEquals(LocalDate.parse("2026-01-28"), result.get(0).date());
        assertEquals(LocalDate.parse("2026-04-28"), result.get(1).date());
    }

    @Test
    @DisplayName("listSnapshots: from만 주어지면 to는 KST today, to만 주어지면 from은 to-90")
    void listSnapshots_partialDefaults() {
        FakeRepository repo = new FakeRepository();
        repo.snapshots.put(LocalDate.parse("2026-04-20"), stubSnapshot("2026-04-20"));
        repo.snapshots.put(LocalDate.parse("2026-04-28"), stubSnapshot("2026-04-28"));

        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        // from만 — to = today (2026-04-28)
        List<SnapshotView> r1 = service.listSnapshots(LocalDate.parse("2026-04-25"), null);
        assertEquals(1, r1.size());
        assertEquals(LocalDate.parse("2026-04-28"), r1.get(0).date());

        // to만 — from = to - 90
        List<SnapshotView> r2 = service.listSnapshots(null, LocalDate.parse("2026-04-21"));
        assertEquals(1, r2.size());
        assertEquals(LocalDate.parse("2026-04-20"), r2.get(0).date());
    }

    @Test
    @DisplayName("응답 메타: usdKrwRate와 KST 오프셋 asOf가 채워진다")
    void view_includesRateAndKstAsOf() {
        FakeRepository repo = new FakeRepository();
        repo.set(new Portfolio());

        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1380.5")));
        PortfolioView view = service.view();

        assertEquals(new BigDecimal("1380.5"), view.usdKrwRate());
        assertNotNull(view.asOf());
        assertEquals(9 * 3600, view.asOf().getOffset().getTotalSeconds(), "KST = +09:00");
    }

    private static SnapshotView stubSnapshot(String isoDate) {
        BigDecimal zero = new BigDecimal("0.0000");
        return new SnapshotView(
                LocalDate.parse(isoDate),
                java.time.OffsetDateTime.parse(isoDate + "T09:00:00+09:00"),
                new BigDecimal("1400"),
                zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                List.of());
    }

    private static Portfolio buildPortfolio(Map<String, Position> positions,
                                            Money cash,
                                            Money cumulativeDeposit,
                                            Money cumulativeWithdraw) {
        Map<String, Position> copy = new HashMap<>();
        positions.forEach((k, v) -> copy.put(k, new Position(v.ticker(), v.qty(), v.avgCost(), v.realizedPnl())));
        return new Portfolio(copy, cash, cumulativeDeposit, cumulativeWithdraw);
    }

    /**
     * 테스트 공용 팩토리 — 빈 META 저장소를 가진 ExchangeResolver 와 함께 서비스를 조립한다.
     * META 가 비어 있으면 resolver 가 NAS 부터 탐색하므로 StubMarketData 가 NAS 호출에 응답하면 된다.
     */
    private static PortfolioApplicationService newService(FakeRepository repo, MarketDataPort market) {
        ExchangeResolver resolver = new ExchangeResolver(market, new InMemoryTickerMetaRepository(), FIXED);
        return new PortfolioApplicationService(repo, market, resolver, FIXED);
    }

    /** 단순 ConcurrentHashMap 기반 META 저장소. 테스트 격리만 보장하면 충분. */
    static class InMemoryTickerMetaRepository implements TickerMetaRepository {
        private final Map<String, TickerMeta> store = new ConcurrentHashMap<>();

        @Override public Optional<TickerMeta> find(String ticker) {
            return Optional.ofNullable(store.get(ticker));
        }
        @Override public void save(TickerMeta meta) {
            store.put(meta.ticker(), meta);
        }
    }

    /** repository fake — load만 의미 있고, recordTrade/listRecentTrades는 단순 미사용. */
    static class FakeRepository implements PortfolioRepository {
        private Portfolio current = new Portfolio();
        final TreeMap<LocalDate, SnapshotView> snapshots = new TreeMap<>();

        void set(Portfolio p) {
            this.current = p;
        }
        @Override public Portfolio load() {
            // 깊은 복사로 외부 mutation 차단
            Map<String, Position> copy = new HashMap<>();
            current.positions().forEach((k, v) ->
                    copy.put(k, new Position(v.ticker(), v.qty(), v.avgCost(), v.realizedPnl())));
            return new Portfolio(copy,
                    current.cashUsd(),
                    current.cumulativeDeposit(),
                    current.cumulativeWithdraw());
        }
        @Override public void recordTrade(Trade trade, Portfolio updatedState) {
            this.current = updatedState;
        }
        @Override public List<Trade> listRecentTrades(int limit) {
            return List.of();
        }
        @Override public List<Trade> listTradesByTicker(String ticker, int limit) {
            return List.of();
        }
        @Override public void saveSnapshot(SnapshotView snapshot) {
            snapshots.put(snapshot.date(), snapshot);
        }
        @Override public List<SnapshotView> findSnapshots(LocalDate from, LocalDate to) {
            return new ArrayList<>(snapshots.subMap(from, true, to, true).values());
        }
    }

    /** MarketDataPort stub. ticker별 가격 또는 실패를 등록할 수 있다. */
    private static class StubMarketData implements MarketDataPort {
        private final BigDecimal rate;
        private final Map<String, BigDecimal> prices = new HashMap<>();
        private final java.util.Set<String> failing = new java.util.HashSet<>();

        StubMarketData(BigDecimal rate) {
            this.rate = rate;
        }

        void put(String ticker, String price) {
            prices.put(ticker, new BigDecimal(price));
        }

        void fail(String ticker) {
            failing.add(ticker);
        }

        @Override
        public Quote getQuote(String ticker, Exchange exchange) {
            if (failing.contains(ticker)) {
                throw new RuntimeException("stub 시세 실패: " + ticker);
            }
            BigDecimal price = prices.get(ticker);
            if (price == null) {
                throw new IllegalStateException("미등록 ticker: " + ticker);
            }
            return new Quote(ticker, exchange, Money.of(price, Currency.USD), Instant.EPOCH);
        }

        @Override
        public BigDecimal getUsdKrwRate() {
            return rate;
        }
    }
}
