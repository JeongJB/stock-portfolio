package com.example.stockportfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Quantity {

    public static final int SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal value;

    public Quantity(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        this.value = value.setScale(SCALE, ROUNDING);
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    public static Quantity zero() {
        return new Quantity(BigDecimal.ZERO);
    }

    public BigDecimal value() {
        return value;
    }

    public Quantity add(Quantity other) {
        return new Quantity(this.value.add(other.value));
    }

    public Quantity subtract(Quantity other) {
        return new Quantity(this.value.subtract(other.value));
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isLessThan(Quantity other) {
        return this.value.compareTo(other.value) < 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity quantity)) return false;
        return value.compareTo(quantity.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
