package com.example.stockportfolio.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Trade(
        String id,
        TradeType type,
        Instant executedAt,
        String ticker,
        Quantity qty,
        Money price,
        Money fee,
        Money cashAmount
) {

    public Trade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(executedAt, "executedAt");
    }

    public Optional<String> tickerOpt() {
        return Optional.ofNullable(ticker);
    }

    public Optional<Quantity> qtyOpt() {
        return Optional.ofNullable(qty);
    }

    public Optional<Money> priceOpt() {
        return Optional.ofNullable(price);
    }

    public Optional<Money> feeOpt() {
        return Optional.ofNullable(fee);
    }

    public Optional<Money> cashAmountOpt() {
        return Optional.ofNullable(cashAmount);
    }

    public static Trade deposit(Instant executedAt, Money amount) {
        return new Trade(UUID.randomUUID().toString(), TradeType.DEPOSIT, executedAt,
                null, null, null, null, amount);
    }

    public static Trade withdraw(Instant executedAt, Money amount) {
        return new Trade(UUID.randomUUID().toString(), TradeType.WITHDRAW, executedAt,
                null, null, null, null, amount);
    }

    public static Trade buy(Instant executedAt, String ticker, Quantity qty, Money price, Money fee) {
        return new Trade(UUID.randomUUID().toString(), TradeType.BUY, executedAt,
                ticker, qty, price, fee, null);
    }

    public static Trade sell(Instant executedAt, String ticker, Quantity qty, Money price, Money fee) {
        return new Trade(UUID.randomUUID().toString(), TradeType.SELL, executedAt,
                ticker, qty, price, fee, null);
    }
}
