package com.example.stockportfolio.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrrCalculatorTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    @DisplayName("XIRR: 1년 전 -100, 오늘 +110 → 약 10%")
    void xirr_simpleOneYearTenPercent() {
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "-100"),
                cf(T0.plus(Duration.ofDays(365)), "110"));

        Optional<BigDecimal> result = IrrCalculator.xirr(flows);

        assertTrue(result.isPresent(), "수렴 결과가 있어야 한다");
        // 365일/365.25일 보정 때문에 정확히 0.10 이 아니라 약 0.1003.
        assertCloseTo(0.1003, result.get(), 1e-3);
    }

    @Test
    @DisplayName("XIRR: 다중 입금 후 회수 → 양수 IRR")
    void xirr_multipleDepositsAndOneWithdrawal() {
        // -100 (0일), -100 (180일), +220 (365일).
        // 두 번째 -100 은 절반만 묶여있어 평균 보유기간 < 1년 → 단순 (220/200-1)=10% 보다 높게 나온다.
        // 정확 해는 약 13.4% — 양수성 + 합리적 범위만 확인.
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "-100"),
                cf(T0.plus(Duration.ofDays(180)), "-100"),
                cf(T0.plus(Duration.ofDays(365)), "220"));

        Optional<BigDecimal> result = IrrCalculator.xirr(flows);

        assertTrue(result.isPresent());
        assertTrue(result.get().signum() > 0,
                "양수 IRR 이어야 한다 (실제: " + result.get() + ")");
        // 0.10 ~ 0.20 범위 — 단순 10% 이상, 50% 미만.
        assertCloseTo(0.135, result.get(), 0.02);
    }

    @Test
    @DisplayName("XIRR: 배당 + 회수 혼합 → 양수 IRR")
    void xirr_dividendAndFinalValue() {
        // -1000 (0일), +50 (90일 배당), +1100 (365일 최종 가치) → 양수 IRR
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "-1000"),
                cf(T0.plus(Duration.ofDays(90)), "50"),
                cf(T0.plus(Duration.ofDays(365)), "1100"));

        Optional<BigDecimal> result = IrrCalculator.xirr(flows);

        assertTrue(result.isPresent());
        assertTrue(result.get().signum() > 0, "양수여야 한다 (실제: " + result.get() + ")");
        // 직관: 1000 → 1150 (배당 + 평가) ≈ 15% 부근
        assertCloseTo(0.15, result.get(), 0.02);
    }

    @Test
    @DisplayName("XIRR: 손실 — 1년 전 -100, 오늘 +90 → 약 -10%")
    void xirr_negativeReturn() {
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "-100"),
                cf(T0.plus(Duration.ofDays(365)), "90"));

        Optional<BigDecimal> result = IrrCalculator.xirr(flows);

        assertTrue(result.isPresent());
        assertTrue(result.get().signum() < 0, "음수여야 한다 (실제: " + result.get() + ")");
        assertCloseTo(-0.10, result.get(), 0.005);
    }

    @Test
    @DisplayName("XIRR: 모두 음수(전부 입금, 회수 0) → empty")
    void xirr_allNegativeReturnsEmpty() {
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "-100"),
                cf(T0.plus(Duration.ofDays(180)), "-200"),
                cf(T0.plus(Duration.ofDays(365)), "0"));

        // 0 은 부호 0 — hasPositive/hasNegative 검사에서 양수 부족으로 empty.
        assertTrue(IrrCalculator.xirr(flows).isEmpty());
    }

    @Test
    @DisplayName("XIRR: 모두 양수 → empty (해 없음)")
    void xirr_allPositiveReturnsEmpty() {
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "100"),
                cf(T0.plus(Duration.ofDays(180)), "200"));

        assertTrue(IrrCalculator.xirr(flows).isEmpty());
    }

    @Test
    @DisplayName("XIRR: flows.size() < 2 → empty")
    void xirr_insufficientFlowsReturnsEmpty() {
        assertTrue(IrrCalculator.xirr(List.of()).isEmpty());
        assertTrue(IrrCalculator.xirr(List.of(cf(T0, "-100"))).isEmpty());
    }

    @Test
    @DisplayName("XIRR: 결과 scale 은 6")
    void xirr_resultScaleIsSix() {
        List<IrrCalculator.CashFlow> flows = List.of(
                cf(T0, "-100"),
                cf(T0.plus(Duration.ofDays(365)), "110"));

        Optional<BigDecimal> result = IrrCalculator.xirr(flows);
        assertTrue(result.isPresent());
        assertEquals(IrrCalculator.RATIO_SCALE, result.get().scale());
    }

    @Test
    @DisplayName("simpleReturn: 110 / 100 → 0.10")
    void simpleReturn_basic() {
        Optional<BigDecimal> r = IrrCalculator.simpleReturn(
                new BigDecimal("110"), new BigDecimal("100"));

        assertTrue(r.isPresent());
        assertEquals(0, r.get().compareTo(new BigDecimal("0.100000")));
    }

    @Test
    @DisplayName("simpleReturn: 손실 80 / 100 → -0.20")
    void simpleReturn_loss() {
        Optional<BigDecimal> r = IrrCalculator.simpleReturn(
                new BigDecimal("80"), new BigDecimal("100"));

        assertTrue(r.isPresent());
        assertEquals(0, r.get().compareTo(new BigDecimal("-0.200000")));
    }

    @Test
    @DisplayName("simpleReturn: 순 원금 0 → empty")
    void simpleReturn_zeroPrincipalReturnsEmpty() {
        assertTrue(IrrCalculator.simpleReturn(
                new BigDecimal("100"), BigDecimal.ZERO).isEmpty());
    }

    @Test
    @DisplayName("simpleReturn: 순 원금 음수(출금이 입금보다 큼) → empty")
    void simpleReturn_negativePrincipalReturnsEmpty() {
        assertTrue(IrrCalculator.simpleReturn(
                new BigDecimal("100"), new BigDecimal("-50")).isEmpty());
    }

    @Test
    @DisplayName("simpleReturn: 결과 scale 은 6")
    void simpleReturn_resultScaleIsSix() {
        Optional<BigDecimal> r = IrrCalculator.simpleReturn(
                new BigDecimal("110"), new BigDecimal("100"));

        assertTrue(r.isPresent());
        assertEquals(IrrCalculator.RATIO_SCALE, r.get().scale());
    }

    private static IrrCalculator.CashFlow cf(Instant at, String amount) {
        return new IrrCalculator.CashFlow(at, new BigDecimal(amount));
    }

    /** double 기준 허용오차로 BigDecimal 비교. */
    private static void assertCloseTo(double expected, BigDecimal actual, double tolerance) {
        double diff = Math.abs(actual.doubleValue() - expected);
        assertTrue(diff <= tolerance,
                "기대값 " + expected + " 와의 차이 " + diff + " 가 허용오차 " + tolerance + " 초과 (실제: " + actual + ")");
    }
}
