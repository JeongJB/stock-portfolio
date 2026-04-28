package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Quote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoQuoteCacheAdapterIT {

    private static final String TABLE_NAME = "PortfolioQuoteTest";
    private static final Instant FIXED_NOW = Instant.parse("2026-04-28T00:00:00Z");
    private static final LocalDate KST_TODAY = LocalDate.of(2026, 4, 28);

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.4"))
            .withExposedPorts(8000);

    private static DynamoDbClient client;
    private DynamoQuoteCacheAdapter adapter;

    @BeforeAll
    static void setUpClient() {
        String endpoint = "http://" + DYNAMO.getHost() + ":" + DYNAMO.getFirstMappedPort();
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @BeforeEach
    void createTable() {
        client.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        adapter = new DynamoQuoteCacheAdapter(client, TABLE_NAME, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void dropTable() {
        try {
            client.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
        } catch (ResourceNotFoundException ignored) {
        }
    }

    @Test
    void put_후_같은_PK_SK로_find하면_저장된_Quote를_반환한다() {
        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("175.50"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        adapter.put(q, KST_TODAY);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, KST_TODAY);

        assertThat(found).isPresent();
        assertThat(found.get().ticker()).isEqualTo("AAPL");
        assertThat(found.get().exchange()).isEqualTo(Exchange.NAS);
        assertThat(found.get().price().amount()).isEqualByComparingTo("175.50");
        assertThat(found.get().asOf()).isEqualTo(Instant.parse("2026-04-27T20:00:00Z"));
    }

    @Test
    void 다른_날짜로_find하면_빈_Optional() {
        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("175.50"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        adapter.put(q, KST_TODAY);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, KST_TODAY.minusDays(1));

        assertThat(found).isEmpty();
    }

    @Test
    void 같은_PK_SK_두번_put하면_마지막_값이_유지된다() {
        Quote first = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("100.00"), Currency.USD),
                Instant.parse("2026-04-27T19:00:00Z"));
        Quote second = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("200.00"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        adapter.put(first, KST_TODAY);
        adapter.put(second, KST_TODAY);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, KST_TODAY);

        assertThat(found).isPresent();
        assertThat(found.get().price().amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void TTL_속성은_저장시각에_36시간을_더한_epoch_second_Number다() {
        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("175.50"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        adapter.put(q, KST_TODAY);

        // raw item 직접 조회
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TICKER#AAPL"),
                        "sk", AttributeValue.fromS("QUOTE#2026-04-28")))
                .build());
        Map<String, AttributeValue> item = response.item();

        assertThat(item).containsKey("ttl");
        AttributeValue ttlAttr = item.get("ttl");
        // Number 타입 확인
        assertThat(ttlAttr.n()).isNotNull();

        long ttl = Long.parseLong(ttlAttr.n());
        long expected = FIXED_NOW.plus(Duration.ofHours(36)).getEpochSecond();
        assertThat(ttl).isEqualTo(expected);
    }

    @Test
    void exchange가_다르면_캐시_미스로_취급된다() {
        Quote nasQuote = new Quote("FOO", Exchange.NAS,
                new Money(new BigDecimal("50.00"), Currency.USD),
                Instant.parse("2026-04-27T20:00:00Z"));
        adapter.put(nasQuote, KST_TODAY);

        // 동일 ticker, 다른 exchange 로 조회
        Optional<Quote> found = adapter.find("FOO", Exchange.NYS, KST_TODAY);

        assertThat(found).isEmpty();
    }

    @Test
    void 빈_캐시에서_find하면_빈_Optional() {
        Optional<Quote> found = adapter.find("UNKNOWN", Exchange.NAS, KST_TODAY);
        assertThat(found).isEmpty();
    }
}
