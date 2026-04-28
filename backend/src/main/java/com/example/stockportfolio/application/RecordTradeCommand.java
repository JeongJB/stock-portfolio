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
        BigDecimal cashAmount
) {

    public Trade toTrade() {
        // tradeId는 서버가 항상 부여 — 클라이언트가 보낸 값은 신뢰하지 않는다
        String id = UUID.randomUUID().toString();
        Instant when = executedAt != null ? executedAt : Instant.now();

        return switch (type) {
            case DEPOSIT -> new Trade(id, TradeType.DEPOSIT, when,
                    null, null, null, null,
                    Money.of(cashAmount, Currency.USD));
            case WITHDRAW -> new Trade(id, TradeType.WITHDRAW, when,
                    null, null, null, null,
                    Money.of(cashAmount, Currency.USD));
            case BUY -> new Trade(id, TradeType.BUY, when,
                    ticker,
                    Quantity.of(qty),
                    Money.of(price, Currency.USD),
                    fee != null ? Money.of(fee, Currency.USD) : Money.zero(Currency.USD),
                    null);
            case SELL -> new Trade(id, TradeType.SELL, when,
                    ticker,
                    Quantity.of(qty),
                    Money.of(price, Currency.USD),
                    fee != null ? Money.of(fee, Currency.USD) : Money.zero(Currency.USD),
                    null);
        };
    }
}
