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
        Money cashAmount,
        String memo
) {

    public static final int MEMO_MAX_LENGTH = 200;

    public Trade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(executedAt, "executedAt");
        // 빈 문자열은 null 로 정규화 — 저장 시 attribute 자체 부재로 다루기 위함.
        if (memo != null && memo.isBlank()) {
            memo = null;
        }
        if (memo != null && memo.length() > MEMO_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "memo 는 최대 " + MEMO_MAX_LENGTH + "자 (입력: " + memo.length() + "자)");
        }
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

    public Optional<String> memoOpt() {
        return Optional.ofNullable(memo);
    }

    public static Trade deposit(Instant executedAt, Money amount) {
        return deposit(executedAt, amount, null);
    }

    public static Trade deposit(Instant executedAt, Money amount, String memo) {
        return new Trade(UUID.randomUUID().toString(), TradeType.DEPOSIT, executedAt,
                null, null, null, null, amount, memo);
    }

    public static Trade withdraw(Instant executedAt, Money amount) {
        return withdraw(executedAt, amount, null);
    }

    public static Trade withdraw(Instant executedAt, Money amount, String memo) {
        return new Trade(UUID.randomUUID().toString(), TradeType.WITHDRAW, executedAt,
                null, null, null, null, amount, memo);
    }

    public static Trade buy(Instant executedAt, String ticker, Quantity qty, Money price, Money fee) {
        return new Trade(UUID.randomUUID().toString(), TradeType.BUY, executedAt,
                ticker, qty, price, fee, null, null);
    }

    public static Trade sell(Instant executedAt, String ticker, Quantity qty, Money price, Money fee) {
        return new Trade(UUID.randomUUID().toString(), TradeType.SELL, executedAt,
                ticker, qty, price, fee, null, null);
    }

    public static Trade dividend(Instant executedAt, String ticker, Money amount) {
        return new Trade(UUID.randomUUID().toString(), TradeType.DIVIDEND, executedAt,
                ticker, null, null, null, amount, null);
    }
}
