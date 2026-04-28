package com.example.stockportfolio.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioTest {

    private static final Instant T0 = Instant.parse("2026-04-28T00:00:00Z");

    private static Money usd(String amount) {
        return Money.of(amount, Currency.USD);
    }

    private static Quantity qty(String value) {
        return Quantity.of(value);
    }

    @Test
    @DisplayName("DEPOSIT은 현금과 누적 입금을 모두 증가시킨다")
    void deposit_increasesCashAndCumulativeDeposit() {
        Portfolio portfolio = new Portfolio();

        portfolio.apply(Trade.deposit(T0, usd("1000")));

        assertEquals(usd("1000"), portfolio.cashUsd());
        assertEquals(usd("1000"), portfolio.cumulativeDeposit());
        assertEquals(usd("0"), portfolio.cumulativeWithdraw());
        assertEquals(usd("1000"), portfolio.principal());
    }

    @Test
    @DisplayName("WITHDRAW는 현금을 차감하고 누적 출금을 증가시킨다")
    void withdraw_decreasesCashAndIncreasesCumulativeWithdraw() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("1000")));

        portfolio.apply(Trade.withdraw(T0, usd("300")));

        assertEquals(usd("700"), portfolio.cashUsd());
        assertEquals(usd("1000"), portfolio.cumulativeDeposit());
        assertEquals(usd("300"), portfolio.cumulativeWithdraw());
        assertEquals(usd("700"), portfolio.principal());
    }

    @Test
    @DisplayName("WITHDRAW 시 잔고 부족이면 도메인 예외")
    void withdraw_insufficientCash_throws() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("100")));

        DomainException ex = assertThrows(DomainException.class,
                () -> portfolio.apply(Trade.withdraw(T0, usd("200"))));
        assertTrue(ex.getMessage().contains("현금 잔고 부족"));
        // 실패 시 상태가 바뀌지 않음을 확인
        assertEquals(usd("100"), portfolio.cashUsd());
        assertEquals(usd("0"), portfolio.cumulativeWithdraw());
    }

    @Test
    @DisplayName("BUY는 현금을 차감하고 포지션을 생성한다")
    void buy_decreasesCashAndOpensPosition() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("10000")));

        // 10주 * 100 USD + 수수료 1 USD = 1001 USD
        portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("1")));

        assertEquals(usd("8999"), portfolio.cashUsd());
        Position pos = portfolio.position("AAPL").orElseThrow();
        assertEquals(qty("10"), pos.qty());
        assertEquals(usd("100"), pos.avgCost());
        assertEquals(usd("0"), pos.realizedPnl());
    }

    @Test
    @DisplayName("BUY 시 현금 부족이면 도메인 예외")
    void buy_insufficientCash_throws() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("100")));

        DomainException ex = assertThrows(DomainException.class,
                () -> portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("1"))));
        assertTrue(ex.getMessage().contains("현금 잔고 부족"));
        assertEquals(usd("100"), portfolio.cashUsd());
        assertTrue(portfolio.position("AAPL").isEmpty());
    }

    @Test
    @DisplayName("추가 매수 시 평균단가는 가중평균으로 갱신된다")
    void buy_additional_weightedAverageCost() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("100000")));

        // 10주 * 100 = 1000
        portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("0")));
        // 추가 30주 * 120 = 3600
        portfolio.apply(Trade.buy(T0, "AAPL", qty("30"), usd("120"), usd("0")));

        // (10*100 + 30*120) / 40 = 4600/40 = 115
        Position pos = portfolio.position("AAPL").orElseThrow();
        assertEquals(qty("40"), pos.qty());
        assertEquals(usd("115"), pos.avgCost());
    }

    @Test
    @DisplayName("SELL은 현금을 늘리고 보유 수량을 줄인다 (평균단가 유지)")
    void sell_increasesCashAndKeepsAvgCost() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("10000")));
        portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("0")));
        // 매수 후 현금: 10000 - 1000 = 9000

        // 4주 * 150 - 1 = 599 USD 입금
        portfolio.apply(Trade.sell(T0, "AAPL", qty("4"), usd("150"), usd("1")));

        assertEquals(usd("9599"), portfolio.cashUsd());
        Position pos = portfolio.position("AAPL").orElseThrow();
        assertEquals(qty("6"), pos.qty());
        // 평균단가는 매도 시 변하지 않는다
        assertEquals(usd("100"), pos.avgCost());
        // 실현 손익: (150-100)*4 - 1 = 199
        assertEquals(usd("199"), pos.realizedPnl());
    }

    @Test
    @DisplayName("SELL 시 보유 수량 부족이면 도메인 예외")
    void sell_insufficientQty_throws() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("10000")));
        portfolio.apply(Trade.buy(T0, "AAPL", qty("5"), usd("100"), usd("0")));

        DomainException ex = assertThrows(DomainException.class,
                () -> portfolio.apply(Trade.sell(T0, "AAPL", qty("10"), usd("150"), usd("0"))));
        assertTrue(ex.getMessage().contains("보유 수량 부족"));
        // 상태 보존
        assertEquals(qty("5"), portfolio.position("AAPL").orElseThrow().qty());
    }

    @Test
    @DisplayName("SELL로 보유 수량이 0이 되면 포지션이 제거된다")
    void sell_toZero_removesPosition() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("10000")));
        portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("0")));

        portfolio.apply(Trade.sell(T0, "AAPL", qty("10"), usd("120"), usd("0")));

        assertTrue(portfolio.position("AAPL").isEmpty());
        assertFalse(portfolio.positions().containsKey("AAPL"));
        // 현금: 9000 + 1200 = 10200
        assertEquals(usd("10200"), portfolio.cashUsd());
    }

    @Test
    @DisplayName("부분 매도 후 평균단가는 유지되고 실현손익이 누적된다")
    void partialSell_avgCostUnchanged_realizedPnlAccumulates() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("100000")));
        portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("0")));

        // 1차 부분 매도: (130-100)*3 = 90
        portfolio.apply(Trade.sell(T0, "AAPL", qty("3"), usd("130"), usd("0")));
        Position pos1 = portfolio.position("AAPL").orElseThrow();
        assertEquals(qty("7"), pos1.qty());
        assertEquals(usd("100"), pos1.avgCost());
        assertEquals(usd("90"), pos1.realizedPnl());

        // 2차 부분 매도: (140-100)*2 = 80, 누적 실현손익 170
        portfolio.apply(Trade.sell(T0, "AAPL", qty("2"), usd("140"), usd("0")));
        Position pos2 = portfolio.position("AAPL").orElseThrow();
        assertEquals(qty("5"), pos2.qty());
        assertEquals(usd("100"), pos2.avgCost());
        assertEquals(usd("170"), pos2.realizedPnl());
    }

    @Test
    @DisplayName("원금은 누적입금-누적출금이며 BUY/SELL은 원금에 영향 없음")
    void principal_unaffectedByBuySell() {
        Portfolio portfolio = new Portfolio();
        portfolio.apply(Trade.deposit(T0, usd("10000")));
        portfolio.apply(Trade.deposit(T0, usd("5000")));
        portfolio.apply(Trade.withdraw(T0, usd("2000")));

        // 입금 15000 - 출금 2000 = 원금 13000
        assertEquals(usd("13000"), portfolio.principal());

        // 매수/매도는 원금에 영향 없음
        portfolio.apply(Trade.buy(T0, "AAPL", qty("10"), usd("100"), usd("1")));
        assertEquals(usd("13000"), portfolio.principal());

        portfolio.apply(Trade.sell(T0, "AAPL", qty("5"), usd("200"), usd("1")));
        assertEquals(usd("13000"), portfolio.principal());

        // 누적 입출금 자체도 BUY/SELL에 흔들리지 않음
        assertEquals(usd("15000"), portfolio.cumulativeDeposit());
        assertEquals(usd("2000"), portfolio.cumulativeWithdraw());
    }
}
