package com.example.stockportfolio.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickerMetaTest {

    private static final Instant T0 = Instant.parse("2026-04-28T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-04-29T00:00:00Z");

    @Test
    void ticker는_대문자로_정규화된다() {
        TickerMeta meta = new TickerMeta("aapl", Exchange.NAS, T0, 0);

        assertThat(meta.ticker()).isEqualTo("AAPL");
    }

    @Test
    void ticker가_빈_문자열이면_예외() {
        assertThatThrownBy(() -> new TickerMeta(" ", Exchange.NAS, T0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어");
    }

    @Test
    void exchange_null이면_예외() {
        assertThatThrownBy(() -> new TickerMeta("AAPL", null, T0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void lastVerifiedAt_null이면_예외() {
        assertThatThrownBy(() -> new TickerMeta("AAPL", Exchange.NAS, null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void consecutiveQuoteFailures가_음수면_예외() {
        assertThatThrownBy(() -> new TickerMeta("AAPL", Exchange.NAS, T0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withSuccess는_거래소와_시각을_갱신하고_카운터를_0으로_리셋한다() {
        TickerMeta before = new TickerMeta("AAPL", Exchange.NAS, T0, 5);

        TickerMeta after = before.withSuccess(Exchange.NYS, T1);

        assertThat(after.ticker()).isEqualTo("AAPL");
        assertThat(after.exchange()).isEqualTo(Exchange.NYS);
        assertThat(after.lastVerifiedAt()).isEqualTo(T1);
        assertThat(after.consecutiveQuoteFailures()).isZero();
    }

    @Test
    void withFailure는_카운터를_1_증가시키고_나머지는_유지한다() {
        TickerMeta before = new TickerMeta("AAPL", Exchange.NAS, T0, 2);

        TickerMeta after = before.withFailure();

        assertThat(after.ticker()).isEqualTo("AAPL");
        assertThat(after.exchange()).isEqualTo(Exchange.NAS);
        assertThat(after.lastVerifiedAt()).isEqualTo(T0);
        assertThat(after.consecutiveQuoteFailures()).isEqualTo(3);
    }
}
