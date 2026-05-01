package com.example.stockportfolio.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeTest {

    private static final Instant T0 = Instant.parse("2026-04-28T00:00:00Z");

    private static Money usd(String amount) {
        return Money.of(amount, Currency.USD);
    }

    @Test
    @DisplayName("memo 가 null 이면 그대로 null")
    void memo_null_remainsNull() {
        Trade t = Trade.deposit(T0, usd("100"));
        assertNull(t.memo());
        assertTrue(t.memoOpt().isEmpty());
    }

    @Test
    @DisplayName("memo blank 는 null 로 정규화된다")
    void memo_blank_normalizedToNull() {
        Trade t = Trade.deposit(T0, usd("100"), "   ");
        assertNull(t.memo());
        assertTrue(t.memoOpt().isEmpty());
    }

    @Test
    @DisplayName("memo 가 200자 이하면 그대로 보존")
    void memo_withinLimit_preserved() {
        String memo = "a".repeat(Trade.MEMO_MAX_LENGTH);
        Trade t = Trade.deposit(T0, usd("100"), memo);
        assertEquals(memo, t.memo());
    }

    @Test
    @DisplayName("memo 가 200자 초과면 IllegalArgumentException")
    void memo_overLimit_throws() {
        String memo = "a".repeat(Trade.MEMO_MAX_LENGTH + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Trade.deposit(T0, usd("100"), memo));
        assertTrue(ex.getMessage().contains("memo"));
    }

    @Test
    @DisplayName("withdraw memo 도 동일하게 보존")
    void withdraw_memo_preserved() {
        Trade t = Trade.withdraw(T0, usd("50"), "월세 송금");
        assertEquals("월세 송금", t.memo());
    }

    @Test
    @DisplayName("memo 가 있는 거래도 도메인 적용 동작은 변하지 않는다")
    void memo_doesNotAffectDomainBehavior() {
        Portfolio p = new Portfolio();
        p.apply(Trade.deposit(T0, usd("1000"), "초기 입금"));
        p.apply(Trade.withdraw(T0, usd("200"), "출금"));
        assertEquals(usd("800"), p.cashUsd());
        assertEquals(usd("800"), p.principal());
    }
}
