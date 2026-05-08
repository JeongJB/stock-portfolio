package com.example.stockportfolio.application;

import com.example.stockportfolio.domain.TradeType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordTradeCommandTest {

    private static final Instant T0 = Instant.parse("2026-04-28T00:00:00Z");

    @Test
    void sector_null_그대로_허용() {
        RecordTradeCommand cmd = newBuy(null);

        assertThat(cmd.sector()).isNull();
    }

    @Test
    void sector_trim_적용된다() {
        RecordTradeCommand cmd = newBuy("  Big Tech  ");

        assertThat(cmd.sector()).isEqualTo("Big Tech");
    }

    @Test
    void sector_빈_문자열은_null_로_정규화() {
        RecordTradeCommand cmd = newBuy("");

        assertThat(cmd.sector()).isNull();
    }

    @Test
    void sector_공백만_있어도_null_로_정규화() {
        RecordTradeCommand cmd = newBuy("   ");

        assertThat(cmd.sector()).isNull();
    }

    @Test
    void sector_30자_이내는_허용() {
        // 정확히 30 자
        String thirty = "012345678901234567890123456789";
        assertThat(thirty.length()).isEqualTo(30);

        RecordTradeCommand cmd = newBuy(thirty);

        assertThat(cmd.sector()).isEqualTo(thirty);
    }

    @Test
    void sector_31자는_IllegalArgumentException() {
        // 31 자
        String thirtyOne = "0123456789012345678901234567890";
        assertThat(thirtyOne.length()).isEqualTo(31);

        assertThatThrownBy(() -> newBuy(thirtyOne))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sector")
                .hasMessageContaining("30");
    }

    @Test
    void sector_trim_후_31자도_거부() {
        // 33 자 raw → trim 후에도 33 자 ⇒ 거부.
        String tooLong = "  abcdefghijklmnopqrstuvwxyz0123456  ";

        assertThatThrownBy(() -> newBuy(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sector_8인자_생성자는_null_로_채운다_기존_호출처_호환() {
        RecordTradeCommand cmd = new RecordTradeCommand(
                TradeType.DEPOSIT, T0,
                null, null, null, null, new BigDecimal("100"), null);

        assertThat(cmd.sector()).isNull();
    }

    private static RecordTradeCommand newBuy(String sector) {
        return new RecordTradeCommand(
                TradeType.BUY, T0,
                "AAPL",
                new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO,
                null, null, sector);
    }
}
