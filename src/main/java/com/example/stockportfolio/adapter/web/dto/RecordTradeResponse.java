package com.example.stockportfolio.adapter.web.dto;

import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import java.time.Instant;

public record RecordTradeResponse(
        String tradeId,
        Instant executedAt,
        TradeType type
) {

    public static RecordTradeResponse from(Trade trade) {
        return new RecordTradeResponse(trade.id(), trade.executedAt(), trade.type());
    }
}
