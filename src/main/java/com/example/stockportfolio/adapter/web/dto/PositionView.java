package com.example.stockportfolio.adapter.web.dto;

import com.example.stockportfolio.domain.Position;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

public record PositionView(
        String ticker,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal qty,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal avgCostUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal realizedPnlUsd
) {

    public static PositionView from(Position p) {
        return new PositionView(
                p.ticker(),
                p.qty().value(),
                p.avgCost().amount(),
                p.realizedPnl().amount());
    }
}
