package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.marketdata.kis.KisAccessTokenStore;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * KIS access token 을 단일 테이블의 META#kis / ACCESS_TOKEN 항목에 박제한다.
 * Lambda 콜드 스타트마다 KIS /oauth2/tokenP 를 호출하지 않도록 24h 유효 토큰을 재사용.
 *
 * NOTE: ttl 속성으로 만료 후 자동 청소된다. AWS 콘솔에서 Portfolio 테이블 TTL 속성을 `ttl` 로
 * 활성화해야 동작 (이미 시세 캐시용으로 활성화됨).
 */
public final class DynamoKisAccessTokenStore implements KisAccessTokenStore {

    private final DynamoDbClient client;
    private final String tableName;
    @SuppressWarnings("unused")
    private final Clock clock;

    public DynamoKisAccessTokenStore(DynamoDbClient client, String tableName, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredToken> find() {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        DynamoAttributes.PK, AttributeValue.fromS(DynamoAttributes.KIS_ACCESS_TOKEN_PK),
                        DynamoAttributes.SK, AttributeValue.fromS(DynamoAttributes.KIS_ACCESS_TOKEN_SK)))
                .consistentRead(false)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DynamoAttributes.kisAccessTokenFromItem(response.item()));
    }

    @Override
    public void save(StoredToken token) {
        Objects.requireNonNull(token, "token");
        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoAttributes.kisAccessTokenItem(token))
                .build());
    }
}
