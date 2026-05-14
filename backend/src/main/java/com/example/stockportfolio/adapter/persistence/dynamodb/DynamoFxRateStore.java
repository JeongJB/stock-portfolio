package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.marketdata.kis.FxRateStore;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * USD/KRW 환율을 단일 테이블의 META#fx / USD_KRW 항목에 박제한다.
 * SnapStart 의 두 번째 init phase 가 KIS EGW00133 (1분당 1회) 에 막혀도, 첫 init 이 박제한 값을
 * 두 번째 init 이 즉시 재사용하므로 snapshot 의 fxCache 가 빈 채로 남지 않는다.
 *
 * NOTE: ttl 속성으로 만료 후 자동 청소된다 (Portfolio 테이블 TTL 속성 `ttl` 이미 활성).
 */
public final class DynamoFxRateStore implements FxRateStore {

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoFxRateStore(DynamoDbClient client, String tableName) {
        this.client = Objects.requireNonNull(client, "client");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public Optional<StoredRate> find() {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        DynamoAttributes.PK, AttributeValue.fromS(DynamoAttributes.FX_PK),
                        DynamoAttributes.SK, AttributeValue.fromS(DynamoAttributes.FX_USD_KRW_SK)))
                .consistentRead(false)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DynamoAttributes.fxRateFromItem(response.item()));
    }

    @Override
    public void save(StoredRate rate) {
        Objects.requireNonNull(rate, "rate");
        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoAttributes.fxRateItem(rate))
                .build());
    }
}
