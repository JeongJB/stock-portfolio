package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.QuoteCachePort;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DynamoDB 단일 테이블에 KST 일자별 시세 캐시를 보존한다. TTL 속성(`ttl`)으로 자동 만료.
 *
 * NOTE: 자동 만료는 AWS 콘솔에서 `Portfolio` 테이블 TTL 속성을 `ttl` 로 활성화해야 동작한다.
 */
public final class DynamoQuoteCacheAdapter implements QuoteCachePort {

    // 36시간 = KST 자정 넘어 익일 EOD까지 여유. 어차피 캐시 hit 판정은 SK(KST 날짜)로 하므로
    // TTL은 stale 항목의 강제 청소 용도이며, 더 짧으면 불필요한 KIS 호출이 늘어난다.
    static final Duration DEFAULT_TTL = Duration.ofHours(36);

    private final DynamoDbClient client;
    private final String tableName;
    private final Clock clock;
    private final Duration ttl;

    public DynamoQuoteCacheAdapter(DynamoDbClient client, String tableName, Clock clock) {
        this(client, tableName, clock, DEFAULT_TTL);
    }

    DynamoQuoteCacheAdapter(DynamoDbClient client, String tableName, Clock clock, Duration ttl) {
        this.client = Objects.requireNonNull(client, "client");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public Optional<Quote> find(String ticker, Exchange exchange, LocalDate kstDate) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        DynamoAttributes.PK, AttributeValue.fromS(DynamoAttributes.tickerPk(ticker)),
                        DynamoAttributes.SK, AttributeValue.fromS(DynamoAttributes.quoteSk(kstDate))))
                .consistentRead(false)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Quote quote = DynamoAttributes.quoteFromItem(response.item());
        // exchange 가 다르면 캐시 미스로 취급 (ticker가 같아도 거래소 다른 종목 충돌 방지).
        if (quote.exchange() != exchange) {
            return Optional.empty();
        }
        return Optional.of(quote);
    }

    @Override
    public void put(Quote quote, LocalDate kstDate) {
        long ttlEpochSecond = clock.instant().plus(ttl).getEpochSecond();
        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoAttributes.quoteItem(quote, kstDate, ttlEpochSecond))
                .build());
    }
}
