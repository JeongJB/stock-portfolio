package com.example.stockportfolio.adapter.web.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 포트폴리오 평가 응답. USD를 기준으로 계산하고 KRW 환산값을 함께 싣는다.
 * weight 합계(positions + cashWeight) = 1.0 (시세 실패 종목 제외).
 */
public record PortfolioView(
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashWeight,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal principalUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal principalKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cumulativeDepositUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cumulativeWithdrawUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalMarketValueUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalMarketValueKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCostBasisUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCostBasisKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalUnrealizedPnlUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalUnrealizedPnlKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal usdKrwRate,
        OffsetDateTime asOf,
        List<PositionView> positions
) {
}
