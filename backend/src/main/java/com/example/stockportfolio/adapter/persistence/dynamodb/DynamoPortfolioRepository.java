package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.CASH_USD_SK;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.GSI1_PK;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.GSI1_SK;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.META_SK;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.PK;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.POSITION_SK_PREFIX;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.SK;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.SNAPSHOT_SK_PREFIX;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.TRADE_SK_PREFIX;
import static com.example.stockportfolio.adapter.persistence.dynamodb.DynamoAttributes.USER_PK;

public final class DynamoPortfolioRepository implements PortfolioRepository {

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoPortfolioRepository(DynamoDbClient client, String tableName) {
        this.client = Objects.requireNonNull(client, "client");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public Portfolio load() {
        Map<String, Position> positions = new HashMap<>();
        Money cashUsd = Money.zero(Currency.USD);
        Money cumulativeDeposit = Money.zero(Currency.USD);
        Money cumulativeWithdraw = Money.zero(Currency.USD);

        // 페이지네이션은 1인 사용자/포지션 수 적음 가정 하에 무시 (P0-2 범위)
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", PK))
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(USER_PK)))
                .build());

        for (Map<String, AttributeValue> item : response.items()) {
            String sk = item.get(SK).s();
            if (sk.startsWith(POSITION_SK_PREFIX)) {
                Position p = DynamoAttributes.positionFromItem(item);
                positions.put(p.ticker(), p);
            } else if (sk.equals(CASH_USD_SK)) {
                cashUsd = DynamoAttributes.cashUsdFromItem(item);
            } else if (sk.equals(META_SK)) {
                Money[] meta = DynamoAttributes.metaFromItem(item);
                cumulativeDeposit = meta[0];
                cumulativeWithdraw = meta[1];
            }
            // TRADE# 아이템은 load에서 무시 (별도 listRecentTrades로 조회)
        }

        return new Portfolio(positions, cashUsd, cumulativeDeposit, cumulativeWithdraw);
    }

    @Override
    public void recordTrade(Trade trade, Portfolio updatedState) {
        Objects.requireNonNull(trade, "trade");
        Objects.requireNonNull(updatedState, "updatedState");

        List<TransactWriteItem> writes = new ArrayList<>();

        // Trade Put (id 중복 방지)
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoAttributes.tradeItem(trade))
                        .conditionExpression("attribute_not_exists(#sk)")
                        .expressionAttributeNames(Map.of("#sk", SK))
                        .build())
                .build());

        // 거래에 연관된 ticker만 갱신 — 나머지 포지션은 변경 없음
        String affectedTicker = trade.ticker();
        if (affectedTicker != null) {
            Position updated = updatedState.positions().get(affectedTicker);
            if (updated == null) {
                // SELL로 전량 매도된 경우: 포지션 삭제
                writes.add(TransactWriteItem.builder()
                        .delete(Delete.builder()
                                .tableName(tableName)
                                .key(Map.of(
                                        PK, AttributeValue.fromS(USER_PK),
                                        SK, AttributeValue.fromS(POSITION_SK_PREFIX + affectedTicker)))
                                .build())
                        .build());
            } else {
                writes.add(TransactWriteItem.builder()
                        .put(Put.builder()
                                .tableName(tableName)
                                .item(DynamoAttributes.positionItem(updated))
                                .build())
                        .build());
            }
        }

        // CASH#USD Put
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoAttributes.cashUsdItem(updatedState.cashUsd()))
                        .build())
                .build());

        // META#PORTFOLIO Put
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoAttributes.metaItem(
                                updatedState.cumulativeDeposit(),
                                updatedState.cumulativeWithdraw()))
                        .build())
                .build());

        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(writes)
                    .build());
        } catch (TransactionCanceledException e) {
            // 가장 흔한 케이스: trade id 중복(condition 실패). 도메인 예외로 래핑.
            throw new DomainException("거래 저장 실패 (중복 또는 조건 위반): " + e.getMessage());
        }
    }

    @Override
    public List<Trade> listRecentTrades(int limit) {
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(false)
                .limit(limit)
                .build());

        List<Trade> trades = new ArrayList<>(response.items().size());
        Set<String> seen = new HashSet<>();
        for (Map<String, AttributeValue> item : response.items()) {
            Trade t = DynamoAttributes.tradeFromItem(item);
            if (seen.add(t.id())) {
                trades.add(t);
            }
        }
        return trades;
    }

    @Override
    public List<Trade> listTradesByType(TradeType type) {
        Objects.requireNonNull(type, "type");
        // SK 오름차순(시간순) 으로 TRADE# prefix 전체 Query 후 type 필터.
        // 1인 사용자 가정 하에 페이지네이션·서버측 필터 expression 없이 단순 클라이언트 필터.
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(true)
                .build());

        List<Trade> trades = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, AttributeValue> item : response.items()) {
            Trade t = DynamoAttributes.tradeFromItem(item);
            if (t.type() == type && seen.add(t.id())) {
                trades.add(t);
            }
        }
        return trades;
    }

    @Override
    public List<Trade> listTradesByTicker(String ticker, int limit) {
        Objects.requireNonNull(ticker, "ticker");
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName("GSI1")
                .keyConditionExpression("#gpk = :gpk AND begins_with(#gsk, :prefix)")
                .expressionAttributeNames(Map.of("#gpk", GSI1_PK, "#gsk", GSI1_SK))
                .expressionAttributeValues(Map.of(
                        ":gpk", AttributeValue.fromS(DynamoAttributes.tickerPk(ticker)),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(false)
                .limit(limit)
                .build());

        List<Trade> trades = new ArrayList<>(response.items().size());
        Set<String> seen = new HashSet<>();
        for (Map<String, AttributeValue> item : response.items()) {
            Trade t = DynamoAttributes.tradeFromItem(item);
            if (seen.add(t.id())) {
                trades.add(t);
            }
        }
        return trades;
    }

    @Override
    public void saveSnapshot(SnapshotView snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        // 같은 날짜 재호출 시 그대로 덮어쓰기 (조건 없음).
        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoAttributes.snapshotItem(snapshot))
                .build());
    }

    @Override
    public List<SnapshotView> findSnapshots(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        // SK BETWEEN — DynamoDB는 inclusive 양쪽. 오름차순 스캔으로 자연스러운 시계열 정렬.
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND #sk BETWEEN :from AND :to")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":from", AttributeValue.fromS(SNAPSHOT_SK_PREFIX + from.toString()),
                        ":to", AttributeValue.fromS(SNAPSHOT_SK_PREFIX + to.toString())))
                .scanIndexForward(true)
                .build());

        List<SnapshotView> snapshots = new ArrayList<>(response.items().size());
        for (Map<String, AttributeValue> item : response.items()) {
            snapshots.add(DynamoAttributes.snapshotFromItem(item));
        }
        return snapshots;
    }
}
