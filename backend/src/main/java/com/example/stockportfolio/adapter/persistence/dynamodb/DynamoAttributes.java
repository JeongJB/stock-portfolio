package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.web.dto.PositionView;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    static final String TTL_ATTR = "ttl";

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int QUOTE_SLOT_MINUTES = 10;
    private static final DateTimeFormatter QUOTE_SLOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(KST);

    // positions 직렬화 전용. JSON 컬럼만 다루므로 모듈 추가는 불필요.
    private static final ObjectMapper SNAPSHOT_MAPPER = JsonMapper.builder().build();

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
        // BUY/SELL 만 종목별 거래 조회용 GSI1 키 박제. DEPOSIT/WITHDRAW 는 키 부재 → 인덱스 자동 제외.
        if (isTickerLinked(trade.type()) && trade.ticker() != null) {
            item.put(GSI1_PK, s(tickerPk(trade.ticker())));
            item.put(GSI1_SK, s(tradeSk(trade)));
        }
        return item;
    }

    private static boolean isTickerLinked(TradeType type) {
        return type == TradeType.BUY || type == TradeType.SELL;
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
        return new Trade(id, type, executedAt, ticker, qty, price, fee, cashAmount);
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
        item.put("positions", s(serializePositions(snapshot.positions())));
        return item;
    }

    static SnapshotView snapshotFromItem(Map<String, AttributeValue> item) {
        return new SnapshotView(
                LocalDate.parse(item.get("date").s()),
                OffsetDateTime.parse(item.get("takenAt").s()),
                new BigDecimal(item.get("usdKrwRate").n()),
                new BigDecimal(item.get("cashUsd").n()),
                new BigDecimal(item.get("cashKrw").n()),
                new BigDecimal(item.get("principalUsd").n()),
                new BigDecimal(item.get("principalKrw").n()),
                new BigDecimal(item.get("totalMarketValueUsd").n()),
                new BigDecimal(item.get("totalMarketValueKrw").n()),
                new BigDecimal(item.get("totalCostBasisUsd").n()),
                new BigDecimal(item.get("totalCostBasisKrw").n()),
                new BigDecimal(item.get("totalUnrealizedPnlUsd").n()),
                new BigDecimal(item.get("totalUnrealizedPnlKrw").n()),
                deserializePositions(item.get("positions").s()));
    }

    private static String serializePositions(List<PositionView> positions) {
        // PositionView 필드는 BigDecimal — 박제 후 환율 변경에도 일관성 유지를 위해 toPlainString으로 보존.
        List<Map<String, Object>> raw = new ArrayList<>(positions.size());
        for (PositionView p : positions) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("ticker", p.ticker());
            entry.put("qty", toPlain(p.qty()));
            entry.put("avgCostUsd", toPlain(p.avgCostUsd()));
            entry.put("avgCostKrw", toPlain(p.avgCostKrw()));
            entry.put("realizedPnlUsd", toPlain(p.realizedPnlUsd()));
            entry.put("lastPriceUsd", toPlain(p.lastPriceUsd()));
            entry.put("lastPriceKrw", toPlain(p.lastPriceKrw()));
            entry.put("marketValueUsd", toPlain(p.marketValueUsd()));
            entry.put("marketValueKrw", toPlain(p.marketValueKrw()));
            entry.put("weight", toPlain(p.weight()));
            entry.put("unrealizedPnlUsd", toPlain(p.unrealizedPnlUsd()));
            entry.put("unrealizedPnlKrw", toPlain(p.unrealizedPnlKrw()));
            raw.add(entry);
        }
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(raw);
        } catch (JacksonException e) {
            throw new IllegalStateException("스냅샷 positions 직렬화 실패", e);
        }
    }

    private static List<PositionView> deserializePositions(String json) {
        try {
            List<Map<String, Object>> raw = SNAPSHOT_MAPPER.readValue(json, new tools.jackson.core.type.TypeReference<>() {});
            List<PositionView> result = new ArrayList<>(raw.size());
            for (Map<String, Object> entry : raw) {
                result.add(new PositionView(
                        (String) entry.get("ticker"),
                        toDecimal(entry.get("qty")),
                        toDecimal(entry.get("avgCostUsd")),
                        toDecimal(entry.get("avgCostKrw")),
                        toDecimal(entry.get("realizedPnlUsd")),
                        toDecimal(entry.get("lastPriceUsd")),
                        toDecimal(entry.get("lastPriceKrw")),
                        toDecimal(entry.get("marketValueUsd")),
                        toDecimal(entry.get("marketValueKrw")),
                        toDecimal(entry.get("weight")),
                        toDecimal(entry.get("unrealizedPnlUsd")),
                        toDecimal(entry.get("unrealizedPnlKrw"))));
            }
            return result;
        } catch (JacksonException e) {
            throw new IllegalStateException("스냅샷 positions 역직렬화 실패", e);
        }
    }

    private static String toPlain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static BigDecimal toDecimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
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
        item.put(TTL_ATTR, AttributeValue.fromN(Long.toString(ttlEpochSecond)));
        return item;
    }

    static com.example.stockportfolio.domain.Quote quoteFromItem(Map<String, AttributeValue> item) {
        String ticker = item.get("ticker").s();
        com.example.stockportfolio.domain.Exchange exchange =
                com.example.stockportfolio.domain.Exchange.valueOf(item.get("exchange").s());
        Money price = new Money(new BigDecimal(item.get("priceUsd").n()), Currency.USD);
        Instant asOf = Instant.parse(item.get("asOf").s());
        return new com.example.stockportfolio.domain.Quote(ticker, exchange, price, asOf);
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
        return item;
    }

    static TickerMeta tickerMetaFromItem(Map<String, AttributeValue> item) {
        String ticker = item.get("ticker").s();
        Exchange exchange = Exchange.valueOf(item.get("exchange").s());
        Instant lastVerifiedAt = Instant.parse(item.get("lastVerifiedAt").s());
        int failures = Integer.parseInt(item.get("consecutiveQuoteFailures").n());
        return new TickerMeta(ticker, exchange, lastVerifiedAt, failures);
    }
}
