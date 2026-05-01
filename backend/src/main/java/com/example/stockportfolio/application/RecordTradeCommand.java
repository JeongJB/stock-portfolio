package com.example.stockportfolio.application;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Quantity;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 컨트롤러 → 유스케이스 경계의 입력 모델. DTO와 도메인 사이를 분리한다.
 */
public record RecordTradeCommand(
        TradeType type,
        Instant executedAt,
        String ticker,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal cashAmount,
        String memo
) {

    public Trade toTrade() {
        // tradeId는 서버가 항상 부여 — 클라이언트가 보낸 값은 신뢰하지 않는다
        String id = UUID.randomUUID().toString();
        Instant when = executedAt != null ? executedAt : Instant.now();

        return switch (type) {
            case DEPOSIT -> new Trade(id, TradeType.DEPOSIT, when,
                    null, null, null, null,
                    cashMoney(cashAmount), memo);
            case WITHDRAW -> new Trade(id, TradeType.WITHDRAW, when,
                    null, null, null, null,
                    cashMoney(cashAmount), memo);
            case BUY -> new Trade(id, TradeType.BUY, when,
                    ticker,
                    qty != null ? Quantity.of(qty) : null,
                    price != null ? Money.of(price, Currency.USD) : null,
                    fee != null ? Money.of(fee, Currency.USD) : Money.zero(Currency.USD),
                    null, memo);
            case SELL -> new Trade(id, TradeType.SELL, when,
                    ticker,
                    qty != null ? Quantity.of(qty) : null,
                    price != null ? Money.of(price, Currency.USD) : null,
                    fee != null ? Money.of(fee, Currency.USD) : Money.zero(Currency.USD),
                    null, memo);
            case DIVIDEND -> new Trade(id, TradeType.DIVIDEND, when,
                    ticker, null, null, null,
                    cashMoney(cashAmount), memo);
        };
    }

    private static Money cashMoney(BigDecimal amount) {
        // null 일 때는 도메인 단계(Portfolio.apply)에서 DomainException 으로 변환되도록 그대로 null 전달.
        return amount != null ? Money.of(amount, Currency.USD) : null;
    }
}
