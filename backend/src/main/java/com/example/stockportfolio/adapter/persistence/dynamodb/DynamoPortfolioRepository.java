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

        // SK 사전순(CASH# < META# < POSITION# < SNAPSHOT# < TRADE#)을 이용해 SNAPSHOT/TRADE 항목은
        // 아예 읽지 않는다. 거래/스냅샷이 누적돼도 view() 비용이 포지션 수에만 비례하도록.
        QueryRequest baseRequest = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND #sk < :limit")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":limit", AttributeValue.fromS(SNAPSHOT_SK_PREFIX)))
                .build();

        for (Map<String, AttributeValue> item : queryAllPages(baseRequest)) {
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
        }

        return new Portfolio(positions, cashUsd, cumulativeDeposit, cumulativeWithdraw);
    }

    /**
     * 1MB 응답 한도를 넘는 Query 의 LastEvaluatedKey 페이지네이션을 모은다. 호출자는 조건이 SK 범위로
     * 충분히 좁혀져 있는지 책임진다 (PK 전체 풀스캔에 그대로 쓰면 여전히 비싸다).
     */
    private List<Map<String, AttributeValue>> queryAllPages(QueryRequest baseRequest) {
        List<Map<String, AttributeValue>> all = new ArrayList<>();
        QueryRequest current = baseRequest;
        while (true) {
            QueryResponse response = client.query(current);
            all.addAll(response.items());
            Map<String, AttributeValue> lastKey = response.lastEvaluatedKey();
            if (lastKey == null || lastKey.isEmpty()) {
                return all;
            }
            current = current.toBuilder().exclusiveStartKey(lastKey).build();
        }
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
        QueryRequest baseRequest = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        return tradesFromItems(queryUpTo(baseRequest, limit), null);
    }

    @Override
    public List<Trade> listAllTrades() {
        QueryRequest baseRequest = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(true)
                .build();

        return tradesFromItems(queryAllPages(baseRequest), null);
    }

    @Override
    public List<Trade> listTradesByType(TradeType type) {
        Objects.requireNonNull(type, "type");
        // 클라이언트 필터: TRADE# 전체를 페이지네이션으로 모은 뒤 type 일치만 추림.
        // FilterExpression 으로 서버측 필터를 걸어도 RCU 는 전 항목 기준이라 비용 동일 → 단순화 우선.
        QueryRequest baseRequest = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(true)
                .build();

        return tradesFromItems(queryAllPages(baseRequest), type);
    }

    @Override
    public List<Trade> listTradesByTicker(String ticker, int limit) {
        Objects.requireNonNull(ticker, "ticker");
        QueryRequest baseRequest = QueryRequest.builder()
                .tableName(tableName)
                .indexName("GSI1")
                .keyConditionExpression("#gpk = :gpk AND begins_with(#gsk, :prefix)")
                .expressionAttributeNames(Map.of("#gpk", GSI1_PK, "#gsk", GSI1_SK))
                .expressionAttributeValues(Map.of(
                        ":gpk", AttributeValue.fromS(DynamoAttributes.tickerPk(ticker)),
                        ":prefix", AttributeValue.fromS(TRADE_SK_PREFIX)))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        return tradesFromItems(queryUpTo(baseRequest, limit), null);
    }

    /**
     * Query 결과 항목들을 Trade 로 매핑하고, typeFilter 가 null 이 아니면 해당 type 만 추린다.
     * 같은 tradeId 가 (이론상 발생하지 않지만) 중복 등장하면 첫 항목만 채택.
     */
    private static List<Trade> tradesFromItems(List<Map<String, AttributeValue>> items, TradeType typeFilter) {
        List<Trade> trades = new ArrayList<>(items.size());
        Set<String> seen = new HashSet<>();
        for (Map<String, AttributeValue> item : items) {
            Trade t = DynamoAttributes.tradeFromItem(item);
            if (typeFilter != null && t.type() != typeFilter) continue;
            if (seen.add(t.id())) {
                trades.add(t);
            }
        }
        return trades;
    }

    /**
     * limit 까지 결과가 모일 때까지 페이지네이션. DynamoDB 의 .limit() 은 평가 항목 수라 첫 응답이
     * 부족하게 올 수 있어 LastEvaluatedKey 가 남아 있으면 추가 fetch.
     */
    private List<Map<String, AttributeValue>> queryUpTo(QueryRequest baseRequest, int limit) {
        List<Map<String, AttributeValue>> all = new ArrayList<>();
        QueryRequest current = baseRequest;
        while (true) {
            QueryResponse response = client.query(current);
            for (Map<String, AttributeValue> item : response.items()) {
                all.add(item);
                if (all.size() >= limit) return all;
            }
            Map<String, AttributeValue> lastKey = response.lastEvaluatedKey();
            if (lastKey == null || lastKey.isEmpty()) {
                return all;
            }
            current = current.toBuilder().exclusiveStartKey(lastKey).build();
        }
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
    public void deleteTradeAndReplaceDerived(Trade tradeToDelete,
                                             Set<String> existingTickers,
                                             Portfolio newState) {
        Objects.requireNonNull(tradeToDelete, "tradeToDelete");
        Objects.requireNonNull(existingTickers, "existingTickers");
        Objects.requireNonNull(newState, "newState");

        List<TransactWriteItem> writes = new ArrayList<>();

        // 1) 거래 1건 Delete (조건부 — 동시 삭제/교차 실행 방지)
        writes.add(TransactWriteItem.builder()
                .delete(Delete.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                PK, AttributeValue.fromS(USER_PK),
                                SK, AttributeValue.fromS(DynamoAttributes.tradeSk(tradeToDelete))))
                        .conditionExpression("attribute_exists(#sk)")
                        .expressionAttributeNames(Map.of("#sk", SK))
                        .build())
                .build());

        // 2) 사라진 ticker 들의 POSITION# Delete
        Set<String> newTickers = newState.positions().keySet();
        for (String ticker : existingTickers) {
            if (!newTickers.contains(ticker)) {
                writes.add(TransactWriteItem.builder()
                        .delete(Delete.builder()
                                .tableName(tableName)
                                .key(Map.of(
                                        PK, AttributeValue.fromS(USER_PK),
                                        SK, AttributeValue.fromS(POSITION_SK_PREFIX + ticker)))
                                .build())
                        .build());
            }
        }

        // 3) 새 상태의 POSITION# 모두 Put (덮어쓰기)
        for (Position p : newState.positions().values()) {
            writes.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(DynamoAttributes.positionItem(p))
                            .build())
                    .build());
        }

        // 4) CASH#USD Put
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoAttributes.cashUsdItem(newState.cashUsd()))
                        .build())
                .build());

        // 5) META#PORTFOLIO Put
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoAttributes.metaItem(
                                newState.cumulativeDeposit(),
                                newState.cumulativeWithdraw()))
                        .build())
                .build());

        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(writes)
                    .build());
        } catch (TransactionCanceledException e) {
            // 거래 미존재(condition 실패) 또는 트랜잭션 한도 초과 등.
            throw new DomainException("거래 삭제/상태 갱신 실패: " + e.getMessage());
        }
    }

    @Override
    public List<SnapshotView> findSnapshots(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        // SK BETWEEN — DynamoDB는 inclusive 양쪽. 오름차순 스캔으로 자연스러운 시계열 정렬.
        QueryRequest baseRequest = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND #sk BETWEEN :from AND :to")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(USER_PK),
                        ":from", AttributeValue.fromS(SNAPSHOT_SK_PREFIX + from.toString()),
                        ":to", AttributeValue.fromS(SNAPSHOT_SK_PREFIX + to.toString())))
                .scanIndexForward(true)
                .build();

        List<Map<String, AttributeValue>> items = queryAllPages(baseRequest);
        List<SnapshotView> snapshots = new ArrayList<>(items.size());
        for (Map<String, AttributeValue> item : items) {
            snapshots.add(DynamoAttributes.snapshotFromItem(item));
        }
        return snapshots;
    }
}
