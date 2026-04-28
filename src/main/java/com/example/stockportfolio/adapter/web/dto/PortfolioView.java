package com.example.stockportfolio.adapter.web.dto;

import com.example.stockportfolio.domain.Portfolio;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PortfolioView(
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cashUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal principalUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cumulativeDepositUsd,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal cumulativeWithdrawUsd,
        List<PositionView> positions
) {

    public static PortfolioView from(Portfolio portfolio) {
        // ticker 사전순으로 정렬 — 응답 안정성을 위해 (HashMap 순서 의존 회피)
        List<PositionView> positions = portfolio.positions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> PositionView.from(e.getValue()))
                .toList();

        return new PortfolioView(
                portfolio.cashUsd().amount(),
                portfolio.principal().amount(),
                portfolio.cumulativeDeposit().amount(),
                portfolio.cumulativeWithdraw().amount(),
                positions);
    }
}
