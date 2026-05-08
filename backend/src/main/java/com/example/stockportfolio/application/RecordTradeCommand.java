package com.example.stockportfolio.application;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Quantity;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 컨트롤러 → 유스케이스 경계의 입력 모델. DTO와 도메인 사이를 분리한다.
 *
 * <p>{@code sector} 는 BUY 거래에서만 의미 있는 자유 입력 분류 라벨. 다른 거래 종류에서는 무시된다.
 * 정규화: 입력은 trim 후 빈 문자열이면 null. 길이는 30 chars (UTF-16 code unit) 까지.
 */
public record RecordTradeCommand(
        TradeType type,
        Instant executedAt,
        String ticker,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal cashAmount,
        String memo,
        String sector
) {

    public static final int SECTOR_MAX_LENGTH = SectorValidator.SECTOR_MAX_LENGTH;

    public RecordTradeCommand {
        // sector 는 BUY 가 아니어도 들어올 수 있지만 (FE 가 잘못 보낸 경우 등) BUY 이외는 어차피 무시.
        // 정규화는 일관 — trim 후 blank 면 null, 길이 초과면 IllegalArgumentException.
        sector = SectorValidator.normalize(sector);
    }

    /** 9-인자 보다 단순한 호출자 (기존 테스트 등) 호환용 8-인자 생성자. sector=null. */
    public RecordTradeCommand(TradeType type, Instant executedAt, String ticker,
                              BigDecimal qty, BigDecimal price, BigDecimal fee,
                              BigDecimal cashAmount, String memo) {
        this(type, executedAt, ticker, qty, price, fee, cashAmount, memo, null);
    }

    public Trade toTrade() {
        // tradeId는 서버가 항상 부여 — 클라이언트가 보낸 값은 신뢰하지 않는다
        String id = UUID.randomUUID().toString();
        Instant when = executedAt != null ? executedAt : Instant.now();

        return switch (type) {
            case DEPOSIT -> new Trade(id, TradeType.DEPOSIT, when,
                    null, null, null, null,
                    cashMoney(cashAmount), memo);
            case WITHDRAW -> new Trade(id, TradeType.WITHDRAW, when,
                    null, null, null, null,
                    cashMoney(cashAmount), memo);
            case BUY -> new Trade(id, TradeType.BUY, when,
                    ticker,
                    qty != null ? Quantity.of(qty) : null,
                    price != null ? Money.of(price, Currency.USD) : null,
                    fee != null ? Money.of(fee, Currency.USD) : Money.zero(Currency.USD),
                    null, memo);
            case SELL -> new Trade(id, TradeType.SELL, when,
                    ticker,
                    qty != null ? Quantity.of(qty) : null,
                    price != null ? Money.of(price, Currency.USD) : null,
                    fee != null ? Money.of(fee, Currency.USD) : Money.zero(Currency.USD),
                    null, memo);
            case DIVIDEND -> new Trade(id, TradeType.DIVIDEND, when,
                    ticker, null, null, null,
                    cashMoney(cashAmount), memo);
        };
    }

    private static Money cashMoney(BigDecimal amount) {
        // null 일 때는 도메인 단계(Portfolio.apply)에서 DomainException 으로 변환되도록 그대로 null 전달.
        return amount != null ? Money.of(amount, Currency.USD) : null;
    }
}
