package com.example.stockportfolio.domain;

import java.util.Objects;

public final class Position {

    private final String ticker;
    private Quantity qty;
    private Money avgCost;
    private Money realizedPnl;

    public Position(String ticker, Quantity qty, Money avgCost, Money realizedPnl) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.qty = Objects.requireNonNull(qty, "qty");
        this.avgCost = Objects.requireNonNull(avgCost, "avgCost");
        this.realizedPnl = Objects.requireNonNull(realizedPnl, "realizedPnl");
    }

    public static Position empty(String ticker, Currency currency) {
        return new Position(ticker, Quantity.zero(), Money.zero(currency), Money.zero(currency));
    }

    public String ticker() {
        return ticker;
    }

    public Quantity qty() {
        return qty;
    }

    public Money avgCost() {
        return avgCost;
    }

    public Money realizedPnl() {
        return realizedPnl;
    }

    void setQty(Quantity qty) {
        this.qty = qty;
    }

    void setAvgCost(Money avgCost) {
        this.avgCost = avgCost;
    }

    void setRealizedPnl(Money realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Position position)) return false;
        return ticker.equals(position.ticker)
                && qty.equals(position.qty)
                && avgCost.equals(position.avgCost)
                && realizedPnl.equals(position.realizedPnl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticker, qty, avgCost, realizedPnl);
    }

    @Override
    public String toString() {
        return "Position{" +
                "ticker='" + ticker + '\'' +
                ", qty=" + qty +
                ", avgCost=" + avgCost +
                ", realizedPnl=" + realizedPnl +
                '}';
    }
}
