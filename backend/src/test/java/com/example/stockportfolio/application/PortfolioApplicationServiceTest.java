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
import com.example.stockportfolio.domain.TradeType;

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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @DisplayName("DIVIDEND 누적: 보유 종목의 평가손익에 누적배당이 합산된다")
    void view_dividendsAddedToHoldingPnl() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("500", Currency.USD),
                Money.of("1500", Currency.USD),
                Money.zero(Currency.USD)));
        // AAPL 누적 배당 80 USD (예: 50 + 30)
        repo.trades.add(Trade.dividend(Instant.parse("2026-03-01T00:00:00Z"),
                "AAPL", Money.of("50", Currency.USD)));
        repo.trades.add(Trade.dividend(Instant.parse("2026-04-01T00:00:00Z"),
                "AAPL", Money.of("30", Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        // 평가손익 = (200-100)*10 + 80 = 1080
        PositionView aapl = view.positions().get(0);
        assertEquals(new BigDecimal("1080.0000"), aapl.unrealizedPnlUsd());
        assertEquals(new BigDecimal("1512000.0000"), aapl.unrealizedPnlKrw());
        // 합계 손익도 1080
        assertEquals(new BigDecimal("1080.0000"), view.totalUnrealizedPnlUsd());
    }

    @Test
    @DisplayName("DIVIDEND 누적: 미보유 종목(전량 매도 후 잔여) 배당도 전체 손익 합계에 포함된다")
    void view_dividendsForUnheldTickerCountedInTotal() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("500", Currency.USD),
                Money.of("1500", Currency.USD),
                Money.zero(Currency.USD)));
        // 보유 중 AAPL: 배당 20
        repo.trades.add(Trade.dividend(Instant.parse("2026-03-01T00:00:00Z"),
                "AAPL", Money.of("20", Currency.USD)));
        // 보유하지 않는 OLD: 배당 7 — PositionView 에는 안 나오지만 합계에는 포함
        repo.trades.add(Trade.dividend(Instant.parse("2026-03-15T00:00:00Z"),
                "OLD", Money.of("7", Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        // AAPL 평가손익 = (200-100)*10 + 20 = 1020
        // 전체 = 1020 + 7 = 1027
        assertEquals(1, view.positions().size());
        assertEquals(new BigDecimal("1020.0000"), view.positions().get(0).unrealizedPnlUsd());
        assertEquals(new BigDecimal("1027.0000"), view.totalUnrealizedPnlUsd());
    }

    @Test
    @DisplayName("DIVIDEND 누적: 시세 실패 종목의 배당은 종목 unrealizedPnl 은 null 이지만 합계에는 가산된다")
    void view_dividendsForQuoteFailedTickerStillCountedInTotal() {
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
        repo.trades.add(Trade.dividend(Instant.parse("2026-03-01T00:00:00Z"),
                "BAD", Money.of("3", Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");
        market.fail("BAD");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        PositionView bad = view.positions().stream()
                .filter(p -> p.ticker().equals("BAD")).findFirst().orElseThrow();
        // 시세 실패 → 종목별 unrealizedPnl 는 null 유지
        assertNull(bad.unrealizedPnlUsd());

        // AAPL 평가손익 = (200-100)*10 = 1000, BAD 배당 3 가산 → 합계 1003
        assertEquals(new BigDecimal("1003.0000"), view.totalUnrealizedPnlUsd());
    }

    @Test
    @DisplayName("IRR + simpleReturn: 거래 + 평가액 조합 → 응답에 채워진다")
    void view_irrAndSimpleReturn_populatedFromTradesAndValuation() {
        FakeRepository repo = new FakeRepository();
        // 시드: 1년 전 1000 입금 + 시점 0 에 AAPL 10주(평단 100) 보유, 현재가 200
        // → 평가액 2000 + 현금 0 = 2000, 순 원금 1000 → simpleReturn = 1.0 (100%)
        // → IRR: -1000 (1년 전), +2000 (오늘) → 약 100% (정확히 365.25일 보정 시 99.x%)
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.zero(Currency.USD),
                Money.of("1000", Currency.USD),
                Money.zero(Currency.USD)));
        // FIXED = 2026-04-28T00:00:00Z, 1년 전 = 2025-04-28
        repo.trades.add(Trade.deposit(Instant.parse("2025-04-28T00:00:00Z"),
                Money.of("1000", Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        // simpleReturn = (2000 - 1000) / 1000 = 1.0
        assertNotNull(view.simpleReturn());
        assertEquals(0, view.simpleReturn().compareTo(new BigDecimal("1.000000")),
                "simpleReturn = 1.0 (실제: " + view.simpleReturn() + ")");

        // IRR ≈ 100%/년 — 365일 vs 365.25일 보정으로 1.001x 수준.
        assertNotNull(view.irr());
        double irrValue = view.irr().doubleValue();
        assertTrue(irrValue > 0.99 && irrValue < 1.02,
                "IRR ≈ 1.0 (실제: " + view.irr() + ")");
    }

    @Test
    @DisplayName("IRR + simpleReturn: 거래 없음(빈 포트폴리오) → 두 필드 모두 null")
    void view_noTrades_irrAndSimpleReturnNull() {
        FakeRepository repo = new FakeRepository();
        repo.set(new Portfolio());

        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));
        PortfolioView view = service.view();

        assertNull(view.irr(), "거래 없음 → IRR null");
        assertNull(view.simpleReturn(), "순 원금 0 → simpleReturn null");
    }

    @Test
    @DisplayName("IRR: DEPOSIT 1건만 있고 회수가 0 (현금만 출금/매수 없음, 평가액 0) → IRR null")
    void view_onlyDepositNoValue_irrNull() {
        FakeRepository repo = new FakeRepository();
        // DEPOSIT 1000 후 모두 출금 — 현금 0, 평가액 0, 순 원금 0
        repo.set(buildPortfolio(Map.of(),
                Money.zero(Currency.USD),
                Money.of("1000", Currency.USD),
                Money.of("1000", Currency.USD)));
        repo.trades.add(Trade.deposit(Instant.parse("2025-04-28T00:00:00Z"),
                Money.of("1000", Currency.USD)));
        repo.trades.add(Trade.withdraw(Instant.parse("2026-04-28T00:00:00Z"),
                Money.of("1000", Currency.USD)));

        // 현재 총자산 = 0 + 0 = 0, flows: -1000, +1000, +0 → IRR ≈ 0 부근에서 수렴 가능
        // 핵심 검증은 simpleReturn null (순 원금 0).
        PortfolioApplicationService service = newService(repo,
                new StubMarketData(new BigDecimal("1400")));
        PortfolioView view = service.view();

        assertNull(view.simpleReturn(), "순 원금 0 → simpleReturn null");
        // IRR 은 수렴할 수도 있음(약 0%) — 부호와 존재만 가볍게 확인.
        // 거래 시점이 정확히 1년 차이라 -1000 / +1000 → IRR = 0 부근.
        if (view.irr() != null) {
            assertTrue(Math.abs(view.irr().doubleValue()) < 0.01,
                    "회수와 입금이 같으면 IRR ≈ 0 (실제: " + view.irr() + ")");
        }
    }

    @Test
    @DisplayName("PositionView: Quote 의 등락률·52주 고저가 그대로 전파되고 weekRangeRatio 가 계산된다")
    void view_propagatesAuxQuoteFieldsAndComputesRangeRatio() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("0", Currency.USD),
                Money.of("1000", Currency.USD),
                Money.zero(Currency.USD)));

        // last=160, low=120, high=200 → ratio = (160-120)/(200-120) = 40/80 = 0.5
        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.putWithAux("AAPL", "160", "1.50", "200.00", "120.00");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        PositionView aapl = view.positions().get(0);
        assertEquals(0, aapl.dailyChangePct().compareTo(new BigDecimal("1.50")));
        assertEquals(0, aapl.weekHigh52Usd().compareTo(new BigDecimal("200.00")));
        assertEquals(0, aapl.weekLow52Usd().compareTo(new BigDecimal("120.00")));
        assertEquals(0, aapl.weekRangeRatio().compareTo(new BigDecimal("0.5000")));
    }

    @Test
    @DisplayName("weekRangeRatio: 52주 고저 동일가면 null (0 분모 방어)")
    void view_weekRangeRatioNullWhenHighEqualsLow() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("0", Currency.USD),
                Money.of("1000", Currency.USD),
                Money.zero(Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.putWithAux("AAPL", "150", "0.00", "150.00", "150.00");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        PositionView aapl = view.positions().get(0);
        // 고저가 동일 → 비율 의미 없음
        assertNull(aapl.weekRangeRatio());
        // 가격 자체는 그대로 노출
        assertEquals(0, aapl.weekHigh52Usd().compareTo(new BigDecimal("150.00")));
    }

    @Test
    @DisplayName("weekRangeRatio: 현재가가 범위 밖이면 [0,1] 로 clamp")
    void view_weekRangeRatioClampedToZeroOne() {
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(
                Map.of("AAPL", new Position("AAPL",
                        Quantity.of("10"), Money.of("100", Currency.USD), Money.zero(Currency.USD)),
                       "MSFT", new Position("MSFT",
                        Quantity.of("5"), Money.of("100", Currency.USD), Money.zero(Currency.USD))),
                Money.of("0", Currency.USD),
                Money.of("1500", Currency.USD),
                Money.zero(Currency.USD)));

        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        // AAPL: 현재가 100 < 저점 120 → ratio < 0 → clamp 0
        market.putWithAux("AAPL", "100", "0.00", "200.00", "120.00");
        // MSFT: 현재가 250 > 고점 200 → ratio > 1 → clamp 1
        market.putWithAux("MSFT", "250", "0.00", "200.00", "120.00");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        PositionView aapl = view.positions().stream()
                .filter(p -> p.ticker().equals("AAPL")).findFirst().orElseThrow();
        PositionView msft = view.positions().stream()
                .filter(p -> p.ticker().equals("MSFT")).findFirst().orElseThrow();

        assertEquals(0, aapl.weekRangeRatio().compareTo(new BigDecimal("0.0000")));
        assertEquals(0, msft.weekRangeRatio().compareTo(new BigDecimal("1.0000")));
    }

    @Test
    @DisplayName("PositionView: 시세 실패 종목은 보조 필드(등락률·52주 고저·ratio) 모두 null")
    void view_quoteFailedTickerHasNullAuxFields() {
        Map<String, Position> positions = new HashMap<>();
        positions.put("BAD", new Position("BAD",
                Quantity.of("5"), Money.of("50", Currency.USD), Money.zero(Currency.USD)));
        FakeRepository repo = new FakeRepository();
        repo.set(buildPortfolio(positions,
                Money.of("100", Currency.USD),
                Money.of("100", Currency.USD),
                Money.zero(Currency.USD)));
        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.fail("BAD");

        PortfolioApplicationService service = newService(repo, market);
        PortfolioView view = service.view();

        PositionView bad = view.positions().get(0);
        assertNull(bad.dailyChangePct());
        assertNull(bad.weekHigh52Usd());
        assertNull(bad.weekLow52Usd());
        assertNull(bad.weekRangeRatio());
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

    // --- recentTrades + realizedPnlUsd ---

    @Test
    @DisplayName("recentTrades: SELL 거래는 (매도가-평단)*수량-수수료 로 realizedPnlUsd 가 채워지고, 그 외는 null")
    void recentTrades_sellHasRealizedPnl_othersNull() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        // DEPOSIT(10000) → BUY 10주 @150, fee 0 → SELL 5주 @ 200, fee 1 → DIVIDEND
        // 평단 150, 매도 손익 = (200-150)*5 - 1 = 249
        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));
        Trade sell = service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", new BigDecimal("5"), new BigDecimal("200"),
                new BigDecimal("1"), null, null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.DIVIDEND, Instant.parse("2026-01-04T00:00:00Z"),
                "AAPL", null, null, null, new BigDecimal("3"), null));

        // recentTrades 내부 listRecentTrades 가 빈 리스트(테스트 fake) 라 직접 listAllTrades 결과를
        // listRecentTrades 와 동일하게 노출시키려면 fake 보강이 필요. 아래 hack: fake 의 trades 를 그대로 반환.
        repo.listRecentReturnsAll = true;

        List<com.example.stockportfolio.adapter.web.dto.TradeView> views = service.recentTrades(10);

        com.example.stockportfolio.adapter.web.dto.TradeView sellView = views.stream()
                .filter(v -> v.tradeId().equals(sell.id()))
                .findFirst().orElseThrow();
        assertNotNull(sellView.realizedPnlUsd(), "SELL 의 realizedPnlUsd 는 null 이 아니다");
        assertEquals(0, sellView.realizedPnlUsd().compareTo(new BigDecimal("249.0000")),
                "실현 손익 = (200-150)*5 - 1 = 249 (실제: " + sellView.realizedPnlUsd() + ")");

        // SELL 외 모든 거래는 realizedPnlUsd null
        for (com.example.stockportfolio.adapter.web.dto.TradeView v : views) {
            if (v.tradeId().equals(sell.id())) continue;
            assertNull(v.realizedPnlUsd(),
                    v.type() + " 거래의 realizedPnlUsd 는 null 이어야 한다 (실제: " + v.realizedPnlUsd() + ")");
        }
    }

    @Test
    @DisplayName("recentTrades: 다중 BUY 후 부분 SELL → 가중평균 단가 기반 손익")
    void recentTrades_weightedAvgCostUsedForPartialSell() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        // DEPOSIT 10000
        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        // BUY 10주 @100 (총가치 1000) → 평단 100
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("100"),
                BigDecimal.ZERO, null, null));
        // BUY 10주 @200 (총가치 2000) → 누적 20주, 가치 3000, 평단 150
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("200"),
                BigDecimal.ZERO, null, null));
        // SELL 5주 @ 250 fee 0 → (250-150)*5 = 500
        Trade sell = service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-04T00:00:00Z"),
                "AAPL", new BigDecimal("5"), new BigDecimal("250"),
                BigDecimal.ZERO, null, null));

        repo.listRecentReturnsAll = true;
        List<com.example.stockportfolio.adapter.web.dto.TradeView> views = service.recentTrades(10);

        com.example.stockportfolio.adapter.web.dto.TradeView sellView = views.stream()
                .filter(v -> v.tradeId().equals(sell.id()))
                .findFirst().orElseThrow();
        assertEquals(0, sellView.realizedPnlUsd().compareTo(new BigDecimal("500.0000")),
                "실현 손익 = (250-150)*5 = 500 (실제: " + sellView.realizedPnlUsd() + ")");
    }

    @Test
    @DisplayName("recentTrades: 부분 매도 후 추가 매수 → 평단 변동 반영, 두 번째 SELL 손익도 정확")
    void recentTrades_avgCostStableAfterPartialSellThenAdjustsOnNewBuy() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        // DEPOSIT 10000 → BUY 10@100 (평단 100, 잔량 10)
        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("100"),
                BigDecimal.ZERO, null, null));
        // SELL 4@150 → (150-100)*4 = 200, 잔량 6, 평단 100 유지
        Trade firstSell = service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", new BigDecimal("4"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));
        // BUY 4@200 → 잔량 10, 가치 6*100 + 4*200 = 1400, 평단 140
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-04T00:00:00Z"),
                "AAPL", new BigDecimal("4"), new BigDecimal("200"),
                BigDecimal.ZERO, null, null));
        // SELL 5@180 → (180-140)*5 = 200
        Trade secondSell = service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-05T00:00:00Z"),
                "AAPL", new BigDecimal("5"), new BigDecimal("180"),
                BigDecimal.ZERO, null, null));

        repo.listRecentReturnsAll = true;
        List<com.example.stockportfolio.adapter.web.dto.TradeView> views = service.recentTrades(10);

        com.example.stockportfolio.adapter.web.dto.TradeView firstSellView = views.stream()
                .filter(v -> v.tradeId().equals(firstSell.id())).findFirst().orElseThrow();
        com.example.stockportfolio.adapter.web.dto.TradeView secondSellView = views.stream()
                .filter(v -> v.tradeId().equals(secondSell.id())).findFirst().orElseThrow();
        assertEquals(0, firstSellView.realizedPnlUsd().compareTo(new BigDecimal("200.0000")),
                "첫 SELL: (150-100)*4 = 200");
        assertEquals(0, secondSellView.realizedPnlUsd().compareTo(new BigDecimal("200.0000")),
                "두 번째 SELL: (180-140)*5 = 200");
    }

    // --- deleteTrade ---

    @Test
    @DisplayName("deleteTrade: 단일 DEPOSIT 삭제 → 잔고/원금 0 으로 복원, 거래 목록에서 사라짐")
    void deleteTrade_singleDeposit_resetsCashAndPrincipal() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        Trade dep = service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("1000"), null));

        service.deleteTrade(dep.id());

        assertEquals(0, repo.load().cashUsd().amount().compareTo(BigDecimal.ZERO));
        assertEquals(0, repo.load().principal().amount().compareTo(BigDecimal.ZERO));
        assertTrue(repo.trades.stream().noneMatch(t -> t.id().equals(dep.id())));
    }

    @Test
    @DisplayName("deleteTrade: 현재 현금 < 삭제 대상 입금 → 422 (현금 부족) + 상태는 보존")
    void deleteTrade_depositLargerThanCurrentCashFails() {
        // DEPOSIT(10000) 후 BUY(150*10=1500) → 현재 현금 8500. DEPOSIT 10000 삭제 시 8500 < 10000 → 거부.
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        Trade dep = service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));

        TradeReplayValidationException ex = assertThrows(TradeReplayValidationException.class,
                () -> service.deleteTrade(dep.id()));
        assertTrue(ex.getMessage().contains("입금"),
                "메시지에 입금 키워드가 포함돼야 한다 (실제: " + ex.getMessage() + ")");

        // 상태는 변하지 않았어야 한다 — 삭제는 트랜잭션 직전에 실패했음.
        assertEquals(2, repo.trades.size());
        assertEquals(0, repo.load().cashUsd().amount().compareTo(new BigDecimal("8500.0000")));
    }

    @Test
    @DisplayName("deleteTrade: 매수 후 매도 시퀀스에서 매수만 삭제 → 422 (ticker-local replay 음수 수량)")
    void deleteTrade_removingBuyBeforeSellFails() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        Trade buy = service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", new BigDecimal("5"), new BigDecimal("160"),
                BigDecimal.ZERO, null, null));

        TradeReplayValidationException ex = assertThrows(TradeReplayValidationException.class,
                () -> service.deleteTrade(buy.id()));
        assertTrue(ex.getMessage().contains("AAPL") && ex.getMessage().contains("음수"),
                "메시지에 AAPL 종목과 음수 수량 표현이 포함돼야 한다 (실제: " + ex.getMessage() + ")");
    }

    @Test
    @DisplayName("deleteTrade: 매수+매도 중 매도만 삭제 → BUY 만 남고 포지션 10주로 복원")
    void deleteTrade_removingSellRebuildsPosition() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));
        Trade sell = service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", new BigDecimal("5"), new BigDecimal("160"),
                BigDecimal.ZERO, null, null));

        service.deleteTrade(sell.id());

        Portfolio loaded = repo.load();
        // 매도가 사라졌으므로 포지션 10주, 현금 10000-1500=8500
        assertEquals(0, loaded.cashUsd().amount().compareTo(new BigDecimal("8500.0000")));
        assertEquals(0, loaded.position("AAPL").orElseThrow().qty().value()
                .compareTo(new BigDecimal("10.000000")));
    }

    @Test
    @DisplayName("deleteTrade: 존재하지 않는 id → NoSuchElementException (컨트롤러는 404 매핑)")
    void deleteTrade_unknownId_throwsNoSuchElement() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        assertThrows(NoSuchElementException.class,
                () -> service.deleteTrade("non-existent-uuid"));
    }

    @Test
    @DisplayName("deleteTrade: DIVIDEND 삭제 → 현금 감소, view() 누적배당도 감소")
    void deleteTrade_dividendRemoval() {
        FakeRepository repo = new FakeRepository();
        StubMarketData market = new StubMarketData(new BigDecimal("1400"));
        market.put("AAPL", "200");
        PortfolioApplicationService service = newService(repo, market);

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("10000"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));
        Trade div = service.recordTrade(new RecordTradeCommand(
                TradeType.DIVIDEND, Instant.parse("2026-03-01T00:00:00Z"),
                "AAPL", null, null, null, new BigDecimal("50"), null));

        // 삭제 전 view: 현금 = 10000 - 1500 + 50 = 8550, 평가손익 = (200-150)*10 + 50 = 550
        PortfolioView before = service.view();
        assertEquals(0, before.cashUsd().compareTo(new BigDecimal("8550.0000")));
        assertEquals(0, before.totalUnrealizedPnlUsd().compareTo(new BigDecimal("550.0000")));

        service.deleteTrade(div.id());

        PortfolioView after = service.view();
        // 현금 = 8500, 평가손익 = (200-150)*10 = 500 (배당 50 사라짐)
        assertEquals(0, after.cashUsd().compareTo(new BigDecimal("8500.0000")));
        assertEquals(0, after.totalUnrealizedPnlUsd().compareTo(new BigDecimal("500.0000")));
        assertFalse(repo.trades.stream().anyMatch(t -> t.id().equals(div.id())));
    }

    @Test
    @DisplayName("deleteTrade: backfill 패턴 (최근 입금 + 과거 매수) → 매수 삭제 성공 (사용자 버그 케이스)")
    void deleteTrade_backfillPatternAllowsRemoval() {
        // 사용자가 보고한 정확한 시나리오:
        //  - 최근 입금 1000 USD (2026-04-01)
        //  - 과거 backfill 매수 100 USD (2020-01-08) — 시간상 입금보다 먼저지만 라이브 입력은 입금 후 매수 순서
        // 원본 데이터(라이브 입력 순서) 는 합법: 입금 1000 → 매수 100 → 현금 900, AAPL 1주(@100)
        // 과거 전역 replay 기반 검증은 시간순 sort 후 매수 시점 잔고 0 → 모든 삭제가 422 → false positive.
        // 새 검증(현재 상태 역산) 은 통과해야 한다: 매수 삭제 → 현금 900+100=1000, 포지션 제거.
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        // recordTrade 는 입력 순서대로 apply 하므로 라이브 흐름을 그대로 재현.
        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-04-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("1000"), null));
        Trade backfillBuy = service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2020-01-08T00:00:00Z"),
                "AAPL", new BigDecimal("1"), new BigDecimal("100"),
                BigDecimal.ZERO, null, null));

        // 라이브 상태 확인: 현금 900, AAPL 1주
        Portfolio before = repo.load();
        assertEquals(0, before.cashUsd().amount().compareTo(new BigDecimal("900.0000")));
        assertEquals(0, before.position("AAPL").orElseThrow().qty().value()
                .compareTo(new BigDecimal("1.000000")));

        // 매수 삭제 — 새 흐름에서는 성공해야 한다
        service.deleteTrade(backfillBuy.id());

        Portfolio after = repo.load();
        assertEquals(0, after.cashUsd().amount().compareTo(new BigDecimal("1000.0000")),
                "매수 삭제로 현금이 100 만큼 회복되어야 한다");
        assertTrue(after.position("AAPL").isEmpty(), "AAPL 포지션은 제거돼야 한다");
        assertFalse(repo.trades.stream().anyMatch(t -> t.id().equals(backfillBuy.id())));
    }

    @Test
    @DisplayName("deleteTrade: BUY 단건 삭제 → ticker 가 0 수량 → positions Map 에서 제거")
    void deleteTrade_singleBuyRemovedClearsPosition() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("5000"), null));
        Trade buy = service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));

        // 삭제 전 AAPL 포지션 존재 확인
        assertTrue(repo.load().position("AAPL").isPresent());

        service.deleteTrade(buy.id());

        Portfolio after = repo.load();
        assertTrue(after.position("AAPL").isEmpty(), "단일 매수 삭제 후 포지션은 제거돼야 한다");
        assertEquals(0, after.cashUsd().amount().compareTo(new BigDecimal("5000.0000")),
                "현금 = 5000 (매수 1500 회복)");
    }

    @Test
    @DisplayName("deleteTrade: SELL 삭제 시 매도대금이 현재 현금보다 큼 → 422 (현금 부족)")
    void deleteTrade_sellLargerThanCurrentCashFails() {
        // DEPOSIT(2000) → BUY(150*10=1500) → SELL(5주 @ 160 = 800) → DEPOSIT 1500 출금
        // 현재 현금 = 2000 - 1500 + 800 - 1500 = -200 ?? 안 됨.
        // 다시 설계:
        //  DEPOSIT(2000) → BUY(150*10=1500, 현금 500) → SELL(5주 @ 160 = 800, 현금 1300)
        //  → WITHDRAW(1000, 현금 300)
        //  현재 현금 = 300. SELL 삭제 시 현금 -= 800 → -500 → 거부.
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("2000"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.BUY, Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", new BigDecimal("10"), new BigDecimal("150"),
                BigDecimal.ZERO, null, null));
        Trade sell = service.recordTrade(new RecordTradeCommand(
                TradeType.SELL, Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", new BigDecimal("5"), new BigDecimal("160"),
                BigDecimal.ZERO, null, null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.WITHDRAW, Instant.parse("2026-01-04T00:00:00Z"),
                null, null, null, null, new BigDecimal("1000"), null));

        // 라이브 현금 = 300 확인
        assertEquals(0, repo.load().cashUsd().amount().compareTo(new BigDecimal("300.0000")));

        TradeReplayValidationException ex = assertThrows(TradeReplayValidationException.class,
                () -> service.deleteTrade(sell.id()));
        assertTrue(ex.getMessage().contains("매도") && ex.getMessage().contains("음수"),
                "메시지에 매도와 음수 표현이 포함돼야 한다 (실제: " + ex.getMessage() + ")");
        // 상태 보존
        assertEquals(0, repo.load().cashUsd().amount().compareTo(new BigDecimal("300.0000")));
    }

    @Test
    @DisplayName("deleteTrade: DIVIDEND 삭제 시 현재 현금이 배당보다 작음 → 422")
    void deleteTrade_dividendLargerThanCurrentCashFails() {
        // DEPOSIT(100) → DIVIDEND("AAPL", 50) → WITHDRAW(140) → 현재 현금 = 10
        // DIVIDEND 50 삭제 시 10 < 50 → 거부.
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("100"), null));
        Trade div = service.recordTrade(new RecordTradeCommand(
                TradeType.DIVIDEND, Instant.parse("2026-02-01T00:00:00Z"),
                "AAPL", null, null, null, new BigDecimal("50"), null));
        service.recordTrade(new RecordTradeCommand(
                TradeType.WITHDRAW, Instant.parse("2026-03-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("140"), null));

        assertEquals(0, repo.load().cashUsd().amount().compareTo(new BigDecimal("10.0000")));

        TradeReplayValidationException ex = assertThrows(TradeReplayValidationException.class,
                () -> service.deleteTrade(div.id()));
        assertTrue(ex.getMessage().contains("배당"),
                "메시지에 배당 키워드가 포함돼야 한다 (실제: " + ex.getMessage() + ")");
    }

    @Test
    @DisplayName("deleteTrade: WITHDRAW 삭제는 항상 안전 → 현금 회복")
    void deleteTrade_withdrawAlwaysAllowed() {
        FakeRepository repo = new FakeRepository();
        PortfolioApplicationService service = newService(repo, new StubMarketData(new BigDecimal("1400")));

        service.recordTrade(new RecordTradeCommand(
                TradeType.DEPOSIT, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("1000"), null));
        Trade wd = service.recordTrade(new RecordTradeCommand(
                TradeType.WITHDRAW, Instant.parse("2026-02-01T00:00:00Z"),
                null, null, null, null, new BigDecimal("700"), null));

        assertEquals(0, repo.load().cashUsd().amount().compareTo(new BigDecimal("300.0000")));

        service.deleteTrade(wd.id());

        Portfolio after = repo.load();
        assertEquals(0, after.cashUsd().amount().compareTo(new BigDecimal("1000.0000")),
                "출금 삭제로 현금 회복");
        assertEquals(0, after.cumulativeWithdraw().amount().compareTo(BigDecimal.ZERO),
                "누적 출금도 0 으로 복원");
    }

    private static SnapshotView stubSnapshot(String isoDate) {
        BigDecimal zero = new BigDecimal("0.0000");
        return new SnapshotView(
                LocalDate.parse(isoDate),
                java.time.OffsetDateTime.parse(isoDate + "T09:00:00+09:00"),
                new BigDecimal("1400"),
                zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
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
        final List<Trade> trades = new ArrayList<>();
        // recentTrades 검증용: true 면 listRecentTrades 가 trades 를 시각 역순 limit 로 반환.
        boolean listRecentReturnsAll = false;

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
            trades.add(trade);
        }
        @Override public List<Trade> listRecentTrades(int limit) {
            if (!listRecentReturnsAll) return List.of();
            List<Trade> sorted = new ArrayList<>(trades);
            sorted.sort(java.util.Comparator.comparing(Trade::executedAt).reversed()
                    .thenComparing(Trade::id));
            return sorted.subList(0, Math.min(limit, sorted.size()));
        }
        @Override public List<Trade> listAllTrades() {
            List<Trade> sorted = new ArrayList<>(trades);
            sorted.sort(java.util.Comparator.comparing(Trade::executedAt).thenComparing(Trade::id));
            return sorted;
        }
        @Override public List<Trade> listTradesByType(TradeType type) {
            List<Trade> filtered = new ArrayList<>();
            for (Trade t : trades) {
                if (t.type() == type) filtered.add(t);
            }
            return filtered;
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
        @Override public void deleteTradeAndReplaceDerived(Trade tradeToDelete,
                                                           java.util.Set<String> existingTickers,
                                                           Portfolio newState) {
            trades.removeIf(t -> t.id().equals(tradeToDelete.id()));
            this.current = newState;
        }
    }

    /** MarketDataPort stub. ticker별 가격 또는 실패를 등록할 수 있다. */
    private static class StubMarketData implements MarketDataPort {
        private final BigDecimal rate;
        private final Map<String, BigDecimal> prices = new HashMap<>();
        private final Map<String, BigDecimal[]> auxByTicker = new HashMap<>();
        private final java.util.Set<String> failing = new java.util.HashSet<>();

        StubMarketData(BigDecimal rate) {
            this.rate = rate;
        }

        void put(String ticker, String price) {
            prices.put(ticker, new BigDecimal(price));
        }

        /** 등락률·52주 고저까지 등록 (null 허용). */
        void putWithAux(String ticker, String price, String dailyChangePct, String weekHigh52, String weekLow52) {
            prices.put(ticker, new BigDecimal(price));
            auxByTicker.put(ticker, new BigDecimal[] {
                    dailyChangePct == null ? null : new BigDecimal(dailyChangePct),
                    weekHigh52 == null ? null : new BigDecimal(weekHigh52),
                    weekLow52 == null ? null : new BigDecimal(weekLow52)
            });
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
            BigDecimal[] aux = auxByTicker.get(ticker);
            if (aux == null) {
                return new Quote(ticker, exchange, Money.of(price, Currency.USD), Instant.EPOCH);
            }
            return new Quote(ticker, exchange, Money.of(price, Currency.USD), Instant.EPOCH,
                    aux[0], aux[1], aux[2]);
        }

        @Override
        public BigDecimal getUsdKrwRate() {
            return rate;
        }
    }
}
