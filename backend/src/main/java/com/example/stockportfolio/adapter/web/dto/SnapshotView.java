package com.example.stockportfolio.adapter.web.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 시계열 박제 본문. {@link PortfolioView}와 거의 동일한 필드를 들고 있되,
 * 박제 시점의 KRW 환산값을 함께 저장해 추후 환율 변경에도 일관된 시계열을 유지한다.
 *
 * date: KST 기준 yyyy-MM-dd. 같은 날짜 재호출 시 덮어쓴다.
 * takenAt: 실제 박제 시각(KST 오프셋).
 */
public record SnapshotView(
        LocalDate date,
        OffsetDateTime takenAt,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal usdKrwRate,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal principalUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal principalKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalMarketValueUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalMarketValueKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCostBasisUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCostBasisKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalUnrealizedPnlUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalUnrealizedPnlKrw,
        // 총자산 = 현금 + 평가액. 박제 시점에 계산해 응답에 싣고, 영속 시엔 별도 attribute 로
        // 저장하지 않는다 (cashUsd + totalMarketValueUsd 로 derive 가능 — 옛 항목도 자연 호환).
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalAssetsUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalAssetsKrw,
        List<PositionView> positions
) {
}
