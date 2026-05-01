package com.example.stockportfolio.adapter.web.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 포트폴리오 평가 응답. USD를 기준으로 계산하고 KRW 환산값을 함께 싣는다.
 * weight 합계(positions + cashWeight) = 1.0 (시세 실패 종목 제외).
 *
 * <p>{@code irr} = 연환산 XIRR (예: 0.125000 = 12.5%/년).
 * {@code simpleReturn} = 단순 누적 수익률 = (총평가액 - 순 원금) / 순 원금.
 * 두 값 모두 USD 기준으로 계산하며 nullable — 거래 부족·수렴 실패·순 원금 ≤ 0 시 null.</p>
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
        // 총자산 = 현금 + 평가액. 단순 합산이지만 응답에 박제해 프론트의 부동소수 합산을 회피.
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalAssetsUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalAssetsKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal usdKrwRate,
        OffsetDateTime asOf,
        List<PositionView> positions,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal irr,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal simpleReturn
) {
}
