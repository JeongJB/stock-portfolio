package com.example.stockportfolio.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 종목 마스터. ticker 와 가장 최근에 시세 조회에 성공한 거래소·시각·연속 실패 카운터를 보존한다.
 *
 * <p>거래소는 종목별로 한 번만 결정해 META 에 박제되며, 연속 시세 실패가 임계치를 넘으면
 * {@code ExchangeResolver} 가 NAS → NYS → AMS 순으로 재탐색해 갱신한다.
 *
 * <p>{@code sector} 는 사용자가 BUY 거래 입력 시 자유 입력하는 분류 라벨(예: "Big Tech", "반도체").
 * 옛 항목 호환을 위해 nullable. 매수 시점에만 갱신되며, BUY 거래에서 sector 가 비어 있으면
 * 기존 값을 유지한다 (덮어쓰지 않음).
 */
public record TickerMeta(
        String ticker,
        Exchange exchange,
        Instant lastVerifiedAt,
        int consecutiveQuoteFailures,
        String sector
) {

    public TickerMeta {
        Objects.requireNonNull(ticker, "ticker");
        ticker = ticker.toUpperCase(Locale.ROOT);
        if (ticker.isBlank()) {
            throw new IllegalArgumentException("ticker 는 비어 있을 수 없다");
        }
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        if (consecutiveQuoteFailures < 0) {
            throw new IllegalArgumentException(
                    "consecutiveQuoteFailures 는 0 이상이어야 한다 (입력: " + consecutiveQuoteFailures + ")");
        }
        // sector 는 도메인 레벨에서 별도 trim/길이 검증 없음 — 호출자(application 계층) 에서 정규화 후 들어온다.
    }

    /** sector 인자를 받지 않는 4-인자 호환 생성자. 기존 호출처 (테스트 등) 가 그대로 컴파일되도록. */
    public TickerMeta(String ticker, Exchange exchange, Instant lastVerifiedAt, int consecutiveQuoteFailures) {
        this(ticker, exchange, lastVerifiedAt, consecutiveQuoteFailures, null);
    }

    /** 시세 조회 성공으로 거래소·시각을 갱신하고 실패 카운터를 0으로 리셋한 새 META. sector 는 보존. */
    public TickerMeta withSuccess(Exchange newExchange, Instant verifiedAt) {
        return new TickerMeta(ticker, newExchange, verifiedAt, 0, sector);
    }

    /** 실패 카운터를 +1 한 새 META. exchange/lastVerifiedAt/sector 는 유지. */
    public TickerMeta withFailure() {
        return new TickerMeta(ticker, exchange, lastVerifiedAt, consecutiveQuoteFailures + 1, sector);
    }

    /** sector 만 교체한 새 META. 다른 필드는 모두 보존. */
    public TickerMeta withSector(String newSector) {
        return new TickerMeta(ticker, exchange, lastVerifiedAt, consecutiveQuoteFailures, newSector);
    }
}
