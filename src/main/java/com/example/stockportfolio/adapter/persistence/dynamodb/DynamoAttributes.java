package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quantity;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 도메인 ↔ DynamoDB AttributeValue 매핑 헬퍼. 어댑터 내부 전용.
 */
final class DynamoAttributes {

    static final String PK = "pk";
    static final String SK = "sk";

    static final String USER_PK = "USER#me";
    static final String TRADE_SK_PREFIX = "TRADE#";
    static final String POSITION_SK_PREFIX = "POSITION#";
    static final String CASH_USD_SK = "CASH#USD";
    static final String META_SK = "META#PORTFOLIO";

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
        return item;
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
}
