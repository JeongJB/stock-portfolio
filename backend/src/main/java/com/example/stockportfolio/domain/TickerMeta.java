package com.example.stockportfolio.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 종목 마스터. ticker 와 가장 최근에 시세 조회에 성공한 거래소·시각·연속 실패 카운터를 보존한다.
 *
 * <p>거래소는 종목별로 한 번만 결정해 META 에 박제되며, 연속 시세 실패가 임계치를 넘으면
 * {@code ExchangeResolver} 가 NAS → NYS → AMS 순으로 재탐색해 갱신한다.
 */
public record TickerMeta(
        String ticker,
        Exchange exchange,
        Instant lastVerifiedAt,
        int consecutiveQuoteFailures
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
    }

    /** 시세 조회 성공으로 거래소·시각을 갱신하고 실패 카운터를 0으로 리셋한 새 META. */
    public TickerMeta withSuccess(Exchange newExchange, Instant verifiedAt) {
        return new TickerMeta(ticker, newExchange, verifiedAt, 0);
    }

    /** 실패 카운터를 +1 한 새 META. exchange/lastVerifiedAt 은 유지. */
    public TickerMeta withFailure() {
        return new TickerMeta(ticker, exchange, lastVerifiedAt, consecutiveQuoteFailures + 1);
    }
}
