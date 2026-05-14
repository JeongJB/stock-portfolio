package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.marketdata.kis.FxRateStore;
import com.example.stockportfolio.adapter.marketdata.kis.KisAccessTokenStore;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quantity;
import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 도메인 ↔ DynamoDB AttributeValue 매핑 헬퍼. 어댑터 내부 전용.
 *
 * NOTE: 시세 캐시(QUOTE#&lt;date&gt;)는 `ttl` 속성(epoch second Number)으로 자동 만료된다.
 * AWS 콘솔에서 `Portfolio` 테이블의 TTL 속성으로 `ttl` 을 활성화해야 실제 만료가 동작한다.
 */
final class DynamoAttributes {

    static final String PK = "pk";
    static final String SK = "sk";
    static final String GSI1_PK = "gsi1pk";
    static final String GSI1_SK = "gsi1sk";

    static final String USER_PK = "USER#me";
    static final String TRADE_SK_PREFIX = "TRADE#";
    static final String POSITION_SK_PREFIX = "POSITION#";
    static final String CASH_USD_SK = "CASH#USD";
    static final String META_SK = "META#PORTFOLIO";
    static final String SNAPSHOT_SK_PREFIX = "SNAPSHOT#";
    static final String TICKER_PK_PREFIX = "TICKER#";
    static final String QUOTE_SK_PREFIX = "QUOTE#";
    static final String TICKER_META_SK = "META";
    static final String KIS_ACCESS_TOKEN_PK = "META#kis";
    static final String KIS_ACCESS_TOKEN_SK = "ACCESS_TOKEN";
    static final String FX_PK = "META#fx";
    static final String FX_USD_KRW_SK = "USD_KRW";
    static final String TTL_ATTR = "ttl";

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int QUOTE_SLOT_MINUTES = 10;
    private static final DateTimeFormatter QUOTE_SLOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(KST);

    private DynamoAttributes() {}

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(BigDecimal value) {
        return AttributeValue.fromN(value.toPlainString());
    }

    static String tradeSk(Trade trade) {
        return TRADE_SK_PREFIX + trade.executedAt().toString() + "#" + trade.id();
    }

    static Map<String, AttributeValue> tradeItem(Trade trade) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(USER_PK));
        item.put(SK, s(tradeSk(trade)));
        item.put("tradeType", s(trade.type().name()));
        item.put("executedAt", s(trade.executedAt().toString()));
        item.put("tradeId", s(trade.id()));
        trade.tickerOpt().ifPresent(t -> item.put("ticker", s(t)));
        trade.qtyOpt().ifPresent(q -> item.put("qty", n(q.value())));
        trade.priceOpt().ifPresent(p -> {
            item.put("price", n(p.amount()));
            item.put("priceCcy", s(p.currency().name()));
        });
        trade.feeOpt().ifPresent(f -> {
            item.put("fee", n(f.amount()));
            item.put("feeCcy", s(f.currency().name()));
        });
        trade.cashAmountOpt().ifPresent(c -> {
            item.put("cashAmount", n(c.amount()));
            item.put("cashCcy", s(c.currency().name()));
        });
        // memo 는 null/blank 면 attribute 자체 부재 (Trade 생성 시 blank → null 정규화 완료).
        trade.memoOpt().ifPresent(m -> item.put("memo", s(m)));
        // BUY/SELL/DIVIDEND 만 종목별 거래 조회용 GSI1 키 박제. DEPOSIT/WITHDRAW 는 키 부재 → 인덱스 자동 제외.
        if (isTickerLinked(trade.type()) && trade.ticker() != null) {
            item.put(GSI1_PK, s(tickerPk(trade.ticker())));
            item.put(GSI1_SK, s(tradeSk(trade)));
        }
        return item;
    }

    private static boolean isTickerLinked(TradeType type) {
        return type == TradeType.BUY || type == TradeType.SELL || type == TradeType.DIVIDEND;
    }

    static Trade tradeFromItem(Map<String, AttributeValue> item) {
        String id = item.get("tradeId").s();
        TradeType type = TradeType.valueOf(item.get("tradeType").s());
        Instant executedAt = Instant.parse(item.get("executedAt").s());
        String ticker = item.containsKey("ticker") ? item.get("ticker").s() : null;
        Quantity qty = item.containsKey("qty") ? Quantity.of(new BigDecimal(item.get("qty").n())) : null;
        Money price = readMoney(item, "price", "priceCcy");
        Money fee = readMoney(item, "fee", "feeCcy");
        Money cashAmount = readMoney(item, "cashAmount", "cashCcy");
        String memo = item.containsKey("memo") ? item.get("memo").s() : null;
        return new Trade(id, type, executedAt, ticker, qty, price, fee, cashAmount, memo);
    }

    static Map<String, AttributeValue> positionItem(Position position) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(USER_PK));
        item.put(SK, s(POSITION_SK_PREFIX + position.ticker()));
        item.put("ticker", s(position.ticker()));
        item.put("qty", n(position.qty().value()));
        item.put("avgCost", n(position.avgCost().amount()));
        item.put("avgCostCcy", s(position.avgCost().currency().name()));
        item.put("realizedPnl", n(position.realizedPnl().amount()));
        item.put("realizedPnlCcy", s(position.realizedPnl().currency().name()));
        return item;
    }

    static Position positionFromItem(Map<String, AttributeValue> item) {
        String ticker = item.get("ticker").s();
        Quantity qty = Quantity.of(new BigDecimal(item.get("qty").n()));
        Money avgCost = new Money(
                new BigDecimal(item.get("avgCost").n()),
                Currency.valueOf(item.get("avgCostCcy").s()));
        Money realizedPnl = new Money(
                new BigDecimal(item.get("realizedPnl").n()),
                Currency.valueOf(item.get("realizedPnlCcy").s()));
        return new Position(ticker, qty, avgCost, realizedPnl);
    }

    static Map<String, AttributeValue> cashUsdItem(Money cashUsd) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(USER_PK));
        item.put(SK, s(CASH_USD_SK));
        item.put("currency", s(cashUsd.currency().name()));
        item.put("balance", n(cashUsd.amount()));
        return item;
    }

    static Money cashUsdFromItem(Map<String, AttributeValue> item) {
        return new Money(
                new BigDecimal(item.get("balance").n()),
                Currency.valueOf(item.get("currency").s()));
    }

    static Map<String, AttributeValue> metaItem(Money cumulativeDeposit, Money cumulativeWithdraw) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(USER_PK));
        item.put(SK, s(META_SK));
        item.put("cumulativeDeposit", n(cumulativeDeposit.amount()));
        item.put("cumulativeWithdraw", n(cumulativeWithdraw.amount()));
        item.put("currency", s(cumulativeDeposit.currency().name()));
        return item;
    }

    static Money[] metaFromItem(Map<String, AttributeValue> item) {
        Currency ccy = Currency.valueOf(item.get("currency").s());
        Money deposit = new Money(new BigDecimal(item.get("cumulativeDeposit").n()), ccy);
        Money withdraw = new Money(new BigDecimal(item.get("cumulativeWithdraw").n()), ccy);
        return new Money[] { deposit, withdraw };
    }

    private static Money readMoney(Map<String, AttributeValue> item, String amountKey, String ccyKey) {
        if (!item.containsKey(amountKey) || !item.containsKey(ccyKey)) return null;
        return new Money(
                new BigDecimal(item.get(amountKey).n()),
                Currency.valueOf(item.get(ccyKey).s()));
    }

    static String snapshotSk(LocalDate date) {
        return SNAPSHOT_SK_PREFIX + date.toString();
    }

    static Map<String, AttributeValue> snapshotItem(SnapshotView snapshot) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(USER_PK));
        item.put(SK, s(snapshotSk(snapshot.date())));
        item.put("date", s(snapshot.date().toString()));
        item.put("takenAt", s(snapshot.takenAt().toString()));
        item.put("usdKrwRate", n(snapshot.usdKrwRate()));
        item.put("cashUsd", n(snapshot.cashUsd()));
        item.put("cashKrw", n(snapshot.cashKrw()));
        item.put("principalUsd", n(snapshot.principalUsd()));
        item.put("principalKrw", n(snapshot.principalKrw()));
        item.put("totalMarketValueUsd", n(snapshot.totalMarketValueUsd()));
        item.put("totalMarketValueKrw", n(snapshot.totalMarketValueKrw()));
        item.put("totalCostBasisUsd", n(snapshot.totalCostBasisUsd()));
        item.put("totalCostBasisKrw", n(snapshot.totalCostBasisKrw()));
        item.put("totalUnrealizedPnlUsd", n(snapshot.totalUnrealizedPnlUsd()));
        item.put("totalUnrealizedPnlKrw", n(snapshot.totalUnrealizedPnlKrw()));
        return item;
    }

    static SnapshotView snapshotFromItem(Map<String, AttributeValue> item) {
        BigDecimal cashUsd = new BigDecimal(item.get("cashUsd").n());
        BigDecimal cashKrw = new BigDecimal(item.get("cashKrw").n());
        BigDecimal totalMarketValueUsd = new BigDecimal(item.get("totalMarketValueUsd").n());
        BigDecimal totalMarketValueKrw = new BigDecimal(item.get("totalMarketValueKrw").n());
        // totalAssets 는 별도 attribute 로 저장하지 않고 read 시 derive — 옛 항목도 자연 호환.
        BigDecimal totalAssetsUsd = cashUsd.add(totalMarketValueUsd);
        BigDecimal totalAssetsKrw = cashKrw.add(totalMarketValueKrw);
        return new SnapshotView(
                LocalDate.parse(item.get("date").s()),
                OffsetDateTime.parse(item.get("takenAt").s()),
                new BigDecimal(item.get("usdKrwRate").n()),
                cashUsd,
                cashKrw,
                new BigDecimal(item.get("principalUsd").n()),
                new BigDecimal(item.get("principalKrw").n()),
                totalMarketValueUsd,
                totalMarketValueKrw,
                new BigDecimal(item.get("totalCostBasisUsd").n()),
                new BigDecimal(item.get("totalCostBasisKrw").n()),
                new BigDecimal(item.get("totalUnrealizedPnlUsd").n()),
                new BigDecimal(item.get("totalUnrealizedPnlKrw").n()),
                totalAssetsUsd,
                totalAssetsKrw);
    }

    static String tickerPk(String ticker) {
        return TICKER_PK_PREFIX + ticker;
    }

    /**
     * KST 기준 10분 단위 floor 슬롯 키. 예: 2026-05-01T13:27:33+09:00 → "QUOTE#202605011320".
     */
    static String quoteSk(Instant asOf) {
        ZonedDateTime kst = asOf.atZone(KST);
        int flooredMinute = (kst.getMinute() / QUOTE_SLOT_MINUTES) * QUOTE_SLOT_MINUTES;
        ZonedDateTime slot = kst.withMinute(flooredMinute).withSecond(0).withNano(0);
        return QUOTE_SK_PREFIX + QUOTE_SLOT_FORMAT.format(slot);
    }

    static Map<String, AttributeValue> quoteItem(
            com.example.stockportfolio.domain.Quote quote,
            Instant asOf,
            long ttlEpochSecond) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(tickerPk(quote.ticker())));
        item.put(SK, s(quoteSk(asOf)));
        item.put("ticker", s(quote.ticker()));
        item.put("exchange", s(quote.exchange().name()));
        item.put("priceUsd", n(quote.price().amount()));
        item.put("asOf", s(quote.asOf().toString()));
        // 등락률·52주 고저는 어댑터에서 누락 가능 → null 인 attribute 는 박제 X (DDB 는 미부재 attribute 자동 처리).
        if (quote.dailyChangePct() != null) {
            item.put("dailyChangePct", n(quote.dailyChangePct()));
        }
        if (quote.weekHigh52() != null) {
            item.put("weekHigh52", n(quote.weekHigh52()));
        }
        if (quote.weekLow52() != null) {
            item.put("weekLow52", n(quote.weekLow52()));
        }
        item.put(TTL_ATTR, AttributeValue.fromN(Long.toString(ttlEpochSecond)));
        return item;
    }

    static com.example.stockportfolio.domain.Quote quoteFromItem(Map<String, AttributeValue> item) {
        String ticker = item.get("ticker").s();
        com.example.stockportfolio.domain.Exchange exchange =
                com.example.stockportfolio.domain.Exchange.valueOf(item.get("exchange").s());
        Money price = new Money(new BigDecimal(item.get("priceUsd").n()), Currency.USD);
        Instant asOf = Instant.parse(item.get("asOf").s());
        // 신구 호환: 기존 ROW 에 새 attribute 부재 시 null 매핑 — 다음 슬롯 갱신 시 자연 보강.
        BigDecimal dailyChangePct = item.containsKey("dailyChangePct")
                ? new BigDecimal(item.get("dailyChangePct").n()) : null;
        BigDecimal weekHigh52 = item.containsKey("weekHigh52")
                ? new BigDecimal(item.get("weekHigh52").n()) : null;
        BigDecimal weekLow52 = item.containsKey("weekLow52")
                ? new BigDecimal(item.get("weekLow52").n()) : null;
        return new com.example.stockportfolio.domain.Quote(
                ticker, exchange, price, asOf, dailyChangePct, weekHigh52, weekLow52);
    }

    static String tickerMetaSk() {
        return TICKER_META_SK;
    }

    static Map<String, AttributeValue> tickerMetaItem(TickerMeta meta) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(tickerPk(meta.ticker())));
        item.put(SK, s(TICKER_META_SK));
        item.put("ticker", s(meta.ticker()));
        item.put("exchange", s(meta.exchange().name()));
        item.put("lastVerifiedAt", s(meta.lastVerifiedAt().toString()));
        item.put("consecutiveQuoteFailures",
                AttributeValue.fromN(Integer.toString(meta.consecutiveQuoteFailures())));
        // sector 는 nullable — null 이면 attribute 부재 (DDB null 회피, 옛 항목과 자연 호환).
        if (meta.sector() != null) {
            item.put("sector", s(meta.sector()));
        }
        return item;
    }

    static TickerMeta tickerMetaFromItem(Map<String, AttributeValue> item) {
        String ticker = item.get("ticker").s();
        Exchange exchange = Exchange.valueOf(item.get("exchange").s());
        Instant lastVerifiedAt = Instant.parse(item.get("lastVerifiedAt").s());
        int failures = Integer.parseInt(item.get("consecutiveQuoteFailures").n());
        // 신구 호환: 옛 항목엔 sector attribute 가 없을 수 있다 → null 매핑.
        String sector = item.containsKey("sector") ? item.get("sector").s() : null;
        return new TickerMeta(ticker, exchange, lastVerifiedAt, failures, sector);
    }

    static Map<String, AttributeValue> kisAccessTokenItem(KisAccessTokenStore.StoredToken token) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(KIS_ACCESS_TOKEN_PK));
        item.put(SK, s(KIS_ACCESS_TOKEN_SK));
        item.put("accessToken", s(token.accessToken()));
        item.put("expiresAt", s(token.expiresAt().toString()));
        // ttl 은 expiresAt 그대로 — 만료 직후 DDB TTL 청소가 동작.
        item.put(TTL_ATTR, AttributeValue.fromN(Long.toString(token.expiresAt().getEpochSecond())));
        return item;
    }

    static KisAccessTokenStore.StoredToken kisAccessTokenFromItem(Map<String, AttributeValue> item) {
        String accessToken = item.get("accessToken").s();
        Instant expiresAt = Instant.parse(item.get("expiresAt").s());
        return new KisAccessTokenStore.StoredToken(accessToken, expiresAt);
    }

    static Map<String, AttributeValue> fxRateItem(FxRateStore.StoredRate rate) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(FX_PK));
        item.put(SK, s(FX_USD_KRW_SK));
        item.put("rate", n(rate.rate()));
        item.put("expiresAt", s(rate.expiresAt().toString()));
        // ttl 은 expiresAt 그대로 — 만료 직후 DDB TTL 청소가 동작.
        item.put(TTL_ATTR, AttributeValue.fromN(Long.toString(rate.expiresAt().getEpochSecond())));
        return item;
    }

    static FxRateStore.StoredRate fxRateFromItem(Map<String, AttributeValue> item) {
        BigDecimal rate = new BigDecimal(item.get("rate").n());
        Instant expiresAt = Instant.parse(item.get("expiresAt").s());
        return new FxRateStore.StoredRate(rate, expiresAt);
    }
}
