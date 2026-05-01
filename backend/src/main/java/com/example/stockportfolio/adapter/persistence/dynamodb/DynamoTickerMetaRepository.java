package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.TickerMetaRepository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 단일 테이블에 종목 마스터(TICKER#&lt;sym&gt; / META) 를 저장한다. 시세 캐시와 같은 PK 를 공유하지만
 * SK prefix(`META` vs `QUOTE#`) 가 달라 충돌하지 않는다.
 */
public final class DynamoTickerMetaRepository implements TickerMetaRepository {

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoTickerMetaRepository(DynamoDbClient client, String tableName) {
        this.client = Objects.requireNonNull(client, "client");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public Optional<TickerMeta> find(String ticker) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        DynamoAttributes.PK, AttributeValue.fromS(DynamoAttributes.tickerPk(ticker)),
                        DynamoAttributes.SK, AttributeValue.fromS(DynamoAttributes.tickerMetaSk())))
                .consistentRead(false)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DynamoAttributes.tickerMetaFromItem(response.item()));
    }

    @Override
    public void save(TickerMeta meta) {
        Objects.requireNonNull(meta, "meta");
        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoAttributes.tickerMetaItem(meta))
                .build());
    }
}
