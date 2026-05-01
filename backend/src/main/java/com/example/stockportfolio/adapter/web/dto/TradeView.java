package com.example.stockportfolio.adapter.web.dto;

import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * type 별로 일부 필드는 null이 될 수 있어 JsonInclude.NON_NULL로 응답에서 제외한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeView(
        String tradeId,
        TradeType type,
        Instant executedAt,
        String ticker,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal qty,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal price,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal fee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashAmount,
        String memo,
        // SELL 거래의 실현 손익 USD. (매도가 - 그 시점 평균단가) × 수량 - 수수료. SELL 외 type 은 null.
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal realizedPnlUsd
) {

    public static TradeView from(Trade trade) {
        return from(trade, null);
    }

    public static TradeView from(Trade trade, BigDecimal realizedPnlUsd) {
        return new TradeView(
                trade.id(),
                trade.type(),
                trade.executedAt(),
                trade.ticker(),
                trade.qty() != null ? trade.qty().value() : null,
                trade.price() != null ? trade.price().amount() : null,
                trade.fee() != null ? trade.fee().amount() : null,
                trade.cashAmount() != null ? trade.cashAmount().amount() : null,
                trade.memo(),
                realizedPnlUsd);
    }
}
