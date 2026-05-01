package com.example.stockportfolio.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 비정기 시점 현금흐름의 연환산 수익률(XIRR) 및 단순 누적 수익률 계산.
 *
 * <p>외부 라이브러리 없이 BigDecimal + double 혼합으로 구현한다.
 * 중간 산술은 double 로 충분(8~12자리 정밀도면 IRR 1e-7 수렴 가능),
 * 결과 표현만 BigDecimal scale 6 으로 반올림.</p>
 */
public final class IrrCalculator {

    /** 365.25 일 = 1년 (윤년 평균치). */
    private static final double DAYS_PER_YEAR = 365.25;
    private static final double EPSILON = 1e-10;
    private static final int MAX_NEWTON_ITERATIONS = 100;
    private static final int MAX_BISECTION_ITERATIONS = 200;
    private static final double INITIAL_GUESS = 0.1;
    /** XIRR 정의역 하한: -99% (이 이하면 (1+r) <= 0 으로 발산). */
    private static final double LOWER_BOUND = -0.99;
    /** XIRR 정의역 상한: 1000% (1인용 포트폴리오 현실적 상한). */
    private static final double UPPER_BOUND = 10.0;

    /** 비율 결과 scale. 0.124567 → 12.4567%. */
    public static final int RATIO_SCALE = 6;

    private IrrCalculator() {
    }

    /**
     * XIRR. 현금흐름 시퀀스(시점 + USD 부호 금액)를 받아 연환산 수익률을 반환.
     * 수렴 실패·정의역 위배·입력 부족 시 Optional.empty().
     *
     * <p>관례: 외부 → 포트폴리오 = 음수, 포트폴리오 → 외부 = 양수.
     * 마지막 시점에 현재 평가액(USD)을 양수로 추가해 호출한다.</p>
     */
    public static Optional<BigDecimal> xirr(List<CashFlow> flows) {
        Objects.requireNonNull(flows, "flows");
        if (flows.size() < 2) {
            return Optional.empty();
        }
        // 모두 같은 부호(전부 ≥0 또는 전부 ≤0)면 IRR 정의 불가.
        boolean hasPositive = false;
        boolean hasNegative = false;
        for (CashFlow cf : flows) {
            int s = cf.amountUsd().signum();
            if (s > 0) hasPositive = true;
            else if (s < 0) hasNegative = true;
        }
        if (!hasPositive || !hasNegative) {
            return Optional.empty();
        }

        // 시점·금액을 double 배열로 변환 (기준 시점 = flows[0].at).
        // flows 가 시간 정렬돼 있지 않아도 t0 만 일관되면 결과는 동일.
        Instant t0 = flows.get(0).at();
        int n = flows.size();
        double[] amounts = new double[n];
        double[] years = new double[n];
        for (int i = 0; i < n; i++) {
            CashFlow cf = flows.get(i);
            amounts[i] = cf.amountUsd().doubleValue();
            long deltaSeconds = cf.at().getEpochSecond() - t0.getEpochSecond();
            years[i] = deltaSeconds / 86_400.0 / DAYS_PER_YEAR;
        }

        // 1) Newton-Raphson 시도.
        Double newton = newtonRaphson(amounts, years, INITIAL_GUESS);
        if (newton != null && Double.isFinite(newton) && newton > LOWER_BOUND && newton < UPPER_BOUND) {
            return Optional.of(toScaled(newton));
        }

        // 2) 발산/실패 시 bisection fallback.
        Double bisect = bisection(amounts, years);
        if (bisect != null) {
            return Optional.of(toScaled(bisect));
        }
        return Optional.empty();
    }

    /**
     * 단순 누적 수익률. (현재 총평가액 - 순 원금) / 순 원금.
     * 순 원금 ≤ 0 이면 정의 불가 → empty.
     */
    public static Optional<BigDecimal> simpleReturn(BigDecimal currentTotalUsd, BigDecimal netPrincipalUsd) {
        Objects.requireNonNull(currentTotalUsd, "currentTotalUsd");
        Objects.requireNonNull(netPrincipalUsd, "netPrincipalUsd");
        if (netPrincipalUsd.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal ratio = currentTotalUsd.subtract(netPrincipalUsd)
                .divide(netPrincipalUsd, RATIO_SCALE, RoundingMode.HALF_UP);
        return Optional.of(ratio);
    }

    /** XIRR 입력 단위. amountUsd 부호는 외부 → 포트폴리오 음수, 포트폴리오 → 외부 양수. */
    public record CashFlow(Instant at, BigDecimal amountUsd) {
        public CashFlow {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(amountUsd, "amountUsd");
        }
    }

    /** Newton-Raphson. 발산·NaN·정의역 위배 시 null 반환. */
    private static Double newtonRaphson(double[] amounts, double[] years, double initialGuess) {
        double r = initialGuess;
        for (int iter = 0; iter < MAX_NEWTON_ITERATIONS; iter++) {
            double f = 0.0;
            double df = 0.0;
            double base = 1.0 + r;
            if (base <= 0.0) {
                // 정의역 이탈 — Newton 으로는 더 못 감.
                return null;
            }
            for (int i = 0; i < amounts.length; i++) {
                double t = years[i];
                double pow = Math.pow(base, t);
                f += amounts[i] / pow;
                df += -t * amounts[i] / (pow * base);
            }
            if (!Double.isFinite(f) || !Double.isFinite(df)) {
                return null;
            }
            if (Math.abs(f) < EPSILON) {
                return r;
            }
            if (df == 0.0) {
                return null;
            }
            double next = r - f / df;
            if (!Double.isFinite(next)) {
                return null;
            }
            // 정의역 가드: (1+r) > 0 유지.
            if (next <= LOWER_BOUND) {
                next = (r + LOWER_BOUND) / 2.0;
            }
            if (Math.abs(next - r) < EPSILON) {
                r = next;
                if (Math.abs(npv(amounts, years, r)) < 1e-6) {
                    return r;
                }
                return null;
            }
            r = next;
        }
        // 반복 한도 초과 → 마지막 r 의 잔차가 작으면 인정.
        if (Math.abs(npv(amounts, years, r)) < 1e-6) {
            return r;
        }
        return null;
    }

    /** Bisection fallback. f(low)·f(high) 부호가 다른 구간을 좁혀 1e-9 이하로. */
    private static Double bisection(double[] amounts, double[] years) {
        double low = LOWER_BOUND;
        double high = UPPER_BOUND;
        double fLow = npv(amounts, years, low);
        double fHigh = npv(amounts, years, high);
        if (!Double.isFinite(fLow) || !Double.isFinite(fHigh)) {
            return null;
        }
        if (fLow == 0.0) return low;
        if (fHigh == 0.0) return high;
        if (fLow * fHigh > 0.0) {
            // 같은 부호 — 구간 안에 해 없음. (XIRR 가 단조라 사실상 입력 모순 케이스.)
            return null;
        }
        for (int iter = 0; iter < MAX_BISECTION_ITERATIONS; iter++) {
            double mid = (low + high) / 2.0;
            double fMid = npv(amounts, years, mid);
            if (!Double.isFinite(fMid)) {
                return null;
            }
            if (Math.abs(fMid) < EPSILON || (high - low) / 2.0 < EPSILON) {
                return mid;
            }
            if (fLow * fMid < 0.0) {
                high = mid;
                fHigh = fMid;
            } else {
                low = mid;
                fLow = fMid;
            }
        }
        return (low + high) / 2.0;
    }

    /** NPV(r) = Σ amount_i / (1+r)^t_i. (1+r) ≤ 0 이면 NaN 반환 가드. */
    private static double npv(double[] amounts, double[] years, double r) {
        double base = 1.0 + r;
        if (base <= 0.0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < amounts.length; i++) {
            sum += amounts[i] / Math.pow(base, years[i]);
        }
        return sum;
    }

    private static BigDecimal toScaled(double value) {
        // double → BigDecimal 직접 변환은 잡음(0.1 → 0.10000000000000000555...) 이 끼므로 MathContext 로 정리.
        return new BigDecimal(value, MathContext.DECIMAL64)
                .setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
