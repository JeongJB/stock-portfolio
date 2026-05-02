package com.example.stockportfolio.adapter.web.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * 포지션 단위 평가 정보. 시세 조회 실패 시 가격·평가액·비중·평가손익 필드는 null로 응답한다.
 * 기본 필드(ticker, qty, avgCostUsd, realizedPnlUsd)는 시세 가용 여부와 무관하게 항상 채워진다.
 *
 * <p>당일 등락률·52주 고저·52주 위치 비율은 KIS 응답에 해당 키가 있을 때만 채워진다.
 * 시세는 잡혔지만 보조 필드만 누락된 경우(노출 종목, KIS 명세 변경 등) 도 가능하다.
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
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal unrealizedPnlKrw,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal dailyChangePct,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal weekHigh52Usd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal weekLow52Usd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal weekRangeRatio
) {
    /**
     * 보조 필드 4종이 없는 호출자(스냅샷 역직렬화 등) 용 호환 생성자.
     */
    public PositionView(
            String ticker,
            BigDecimal qty,
            BigDecimal avgCostUsd,
            BigDecimal avgCostKrw,
            BigDecimal realizedPnlUsd,
            BigDecimal lastPriceUsd,
            BigDecimal lastPriceKrw,
            BigDecimal marketValueUsd,
            BigDecimal marketValueKrw,
            BigDecimal weight,
            BigDecimal unrealizedPnlUsd,
            BigDecimal unrealizedPnlKrw) {
        this(ticker, qty, avgCostUsd, avgCostKrw, realizedPnlUsd,
                lastPriceUsd, lastPriceKrw, marketValueUsd, marketValueKrw,
                weight, unrealizedPnlUsd, unrealizedPnlKrw,
                null, null, null, null);
    }
}
