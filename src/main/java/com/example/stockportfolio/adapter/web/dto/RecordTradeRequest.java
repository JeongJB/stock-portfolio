package com.example.stockportfolio.adapter.web.dto;

import com.example.stockportfolio.application.RecordTradeCommand;
import com.example.stockportfolio.domain.TradeType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * BigDecimal 필드는 Jackson이 String도 자동 파싱하므로 클라이언트는 문자열로 보내 정밀도를 보존한다.
 * type별 필수 필드는 RecordTradeCommand.toTrade() 단계에서 확인된다.
 */
public record RecordTradeRequest(
        @NotNull TradeType type,
        Instant executedAt,
        String ticker,
        @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
        @DecimalMin(value = "0", inclusive = false) BigDecimal price,
        @DecimalMin(value = "0") BigDecimal fee,
        @DecimalMin(value = "0", inclusive = false) BigDecimal cashAmount
) {

    public RecordTradeCommand toCommand() {
        return new RecordTradeCommand(type, executedAt, ticker, qty, price, fee, cashAmount);
    }
}
