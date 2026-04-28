package com.example.stockportfolio.adapter.web.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * 포지션 단위 평가 정보. 시세 조회 실패 시 가격·평가액·비중·평가손익 필드는 null로 응답한다.
 * 기본 필드(ticker, qty, avgCostUsd, realizedPnlUsd)는 시세 가용 여부와 무관하게 항상 채워진다.
 */
public record PositionView(
        String ticker,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal qty,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal avgCostUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal avgCostKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal realizedPnlUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal lastPriceUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal lastPriceKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal marketValueUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal marketValueKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal weight,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal unrealizedPnlUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal unrealizedPnlKrw
) {
}
