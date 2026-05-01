package com.example.stockportfolio.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Portfolio {

    private final Map<String, Position> positions;
    private Money cashUsd;
    private Money cumulativeDeposit;
    private Money cumulativeWithdraw;

    public Portfolio() {
        this.positions = new HashMap<>();
        this.cashUsd = Money.zero(Currency.USD);
        this.cumulativeDeposit = Money.zero(Currency.USD);
        this.cumulativeWithdraw = Money.zero(Currency.USD);
    }

    public Portfolio(Map<String, Position> positions,
                     Money cashUsd,
                     Money cumulativeDeposit,
                     Money cumulativeWithdraw) {
        this.positions = new HashMap<>(Objects.requireNonNull(positions, "positions"));
        this.cashUsd = Objects.requireNonNull(cashUsd, "cashUsd");
        this.cumulativeDeposit = Objects.requireNonNull(cumulativeDeposit, "cumulativeDeposit");
        this.cumulativeWithdraw = Objects.requireNonNull(cumulativeWithdraw, "cumulativeWithdraw");
    }

    public Map<String, Position> positions() {
        return Collections.unmodifiableMap(positions);
    }

    public Optional<Position> position(String ticker) {
        return Optional.ofNullable(positions.get(ticker));
    }

    public Money cashUsd() {
        return cashUsd;
    }

    public Money cumulativeDeposit() {
        return cumulativeDeposit;
    }

    public Money cumulativeWithdraw() {
        return cumulativeWithdraw;
    }

    public Money principal() {
        return cumulativeDeposit.subtract(cumulativeWithdraw);
    }

    public void apply(Trade trade) {
        Objects.requireNonNull(trade, "trade");
        switch (trade.type()) {
            case DEPOSIT -> applyDeposit(trade);
            case WITHDRAW -> applyWithdraw(trade);
            case BUY -> applyBuy(trade);
            case SELL -> applySell(trade);
            case DIVIDEND -> applyDividend(trade);
        }
    }

    private void applyDeposit(Trade trade) {
        Money amount = requireCashAmount(trade);
        requirePositive(amount, "DEPOSIT 금액은 0보다 커야 한다");
        cashUsd = cashUsd.add(amount);
        cumulativeDeposit = cumulativeDeposit.add(amount);
    }

    private void applyWithdraw(Trade trade) {
        Money amount = requireCashAmount(trade);
        requirePositive(amount, "WITHDRAW 금액은 0보다 커야 한다");
        if (cashUsd.isLessThan(amount)) {
            throw new DomainException("현금 잔고 부족: " + cashUsd + " < " + amount);
        }
        cashUsd = cashUsd.subtract(amount);
        cumulativeWithdraw = cumulativeWithdraw.add(amount);
    }

    private void applyBuy(Trade trade) {
        String ticker = requireTicker(trade);
        Quantity qty = requireQty(trade);
        Money price = requirePrice(trade);
        Money fee = trade.feeOpt().orElse(Money.zero(Currency.USD));
        requirePositive(qty, "BUY 수량은 0보다 커야 한다");

        Money cost = price.multiply(qty.value()).add(fee);
        if (cashUsd.isLessThan(cost)) {
            throw new DomainException("현금 잔고 부족: " + cashUsd + " < " + cost);
        }
        cashUsd = cashUsd.subtract(cost);

        Position position = positions.computeIfAbsent(ticker,
                t -> Position.empty(t, price.currency()));

        // 가중평균 단가: (기존수량*기존단가 + 신규수량*신규단가) / (기존수량+신규수량)
        // 매수 수수료는 단가에 분배하지 않고 현금에서만 차감 (단순화)
        Quantity newQty = position.qty().add(qty);
        BigDecimal existingValue = position.avgCost().amount().multiply(position.qty().value());
        BigDecimal incomingValue = price.amount().multiply(qty.value());
        BigDecimal newAvg = existingValue.add(incomingValue)
                .divide(newQty.value(), Money.SCALE, Money.ROUNDING);

        position.setQty(newQty);
        position.setAvgCost(Money.of(newAvg, price.currency()));
    }

    private void applyDividend(Trade trade) {
        // ticker 는 박제 목적(종목별 누적 배당 합산)이며 보유 여부 검증은 하지 않는다 —
        // 매도 직후 권리락 이전 보유분에 대한 배당이 늦게 들어오는 케이스를 허용해야 한다.
        String ticker = requireTicker(trade);
        if (ticker.isBlank()) {
            throw new DomainException("DIVIDEND 거래에는 ticker 가 필요하다");
        }
        Money amount = requireCashAmount(trade);
        requirePositive(amount, "DIVIDEND 금액은 0보다 커야 한다");
        cashUsd = cashUsd.add(amount);
    }

    private void applySell(Trade trade) {
        String ticker = requireTicker(trade);
        Quantity qty = requireQty(trade);
        Money price = requirePrice(trade);
        Money fee = trade.feeOpt().orElse(Money.zero(Currency.USD));
        requirePositive(qty, "SELL 수량은 0보다 커야 한다");

        Position position = positions.get(ticker);
        if (position == null || position.qty().isLessThan(qty)) {
            Quantity owned = position == null ? Quantity.zero() : position.qty();
            throw new DomainException("보유 수량 부족: " + ticker + " " + owned + " < " + qty);
        }

        Money proceeds = price.multiply(qty.value()).subtract(fee);
        cashUsd = cashUsd.add(proceeds);

        // 실현 손익 = (매도가 - 평균단가) * 수량 - 수수료
        BigDecimal pnlPerShare = price.amount().subtract(position.avgCost().amount());
        Money realized = Money.of(pnlPerShare.multiply(qty.value()), price.currency()).subtract(fee);
        position.setRealizedPnl(position.realizedPnl().add(realized));

        Quantity newQty = position.qty().subtract(qty);
        if (newQty.isZero()) {
            // 전량 매도: 평균단가 의미 없으므로 포지션 자체를 제거
            positions.remove(ticker);
        } else {
            position.setQty(newQty);
        }
    }

    private static Money requireCashAmount(Trade trade) {
        return trade.cashAmountOpt()
                .orElseThrow(() -> new DomainException(trade.type() + " 거래에는 cashAmount 가 필요하다"));
    }

    private static String requireTicker(Trade trade) {
        return trade.tickerOpt()
                .orElseThrow(() -> new DomainException(trade.type() + " 거래에는 ticker 가 필요하다"));
    }

    private static Quantity requireQty(Trade trade) {
        return trade.qtyOpt()
                .orElseThrow(() -> new DomainException(trade.type() + " 거래에는 qty 가 필요하다"));
    }

    private static Money requirePrice(Trade trade) {
        return trade.priceOpt()
                .orElseThrow(() -> new DomainException(trade.type() + " 거래에는 price 가 필요하다"));
    }

    private static void requirePositive(Money money, String message) {
        if (money.isNegative() || money.isZero()) {
            throw new DomainException(message);
        }
    }

    private static void requirePositive(Quantity qty, String message) {
        if (qty.isNegative() || qty.isZero()) {
            throw new DomainException(message);
        }
    }
}
