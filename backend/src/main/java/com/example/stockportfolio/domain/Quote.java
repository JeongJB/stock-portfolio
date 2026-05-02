package com.example.stockportfolio.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 시세 1건. 기본 필드(ticker/exchange/price/asOf)는 시세 어댑터에서 항상 채워지고,
 * 등락률·52주 고저는 KIS 응답에 해당 키가 있을 때만 채워진다 (없으면 null).
 *
 * <p>52주 고저는 한 쌍 단위로만 의미가 있으므로 어댑터/캐시에서 둘 중 하나가 null 이면
 * 둘 다 null 로 정규화한다.
 */
public record Quote(
        String ticker,
        Exchange exchange,
        Money price,
        Instant asOf,
        BigDecimal dailyChangePct,
        BigDecimal weekHigh52,
        BigDecimal weekLow52) {

    /** 기본 시세만 있는 단순 케이스용 보조 생성자 (테스트·호환 호출자에서 사용). */
    public Quote(String ticker, Exchange exchange, Money price, Instant asOf) {
        this(ticker, exchange, price, asOf, null, null, null);
    }
}
