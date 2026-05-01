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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoQuoteCacheAdapterIT {

    private static final String TABLE_NAME = "PortfolioQuoteTest";
    // 2026-05-01 13:23:00 KST → 2026-05-01 04:23:00Z. 10분 floor → 13:20 KST → SK "QUOTE#202605011320"
    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T04:23:00Z");
    private static final String EXPECTED_SLOT_SK = "QUOTE#202605011320";

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
    void put_후_같은_슬롯_시각으로_find하면_저장된_Quote를_반환한다() {
        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("175.50"), Currency.USD),
                Instant.parse("2026-05-01T04:23:00Z"));
        adapter.put(q, FIXED_NOW);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, FIXED_NOW);

        assertThat(found).isPresent();
        assertThat(found.get().ticker()).isEqualTo("AAPL");
        assertThat(found.get().exchange()).isEqualTo(Exchange.NAS);
        assertThat(found.get().price().amount()).isEqualByComparingTo("175.50");
        assertThat(found.get().asOf()).isEqualTo(Instant.parse("2026-05-01T04:23:00Z"));
    }

    @Test
    void 다른_슬롯_시각으로_find하면_빈_Optional() {
        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("175.50"), Currency.USD),
                Instant.parse("2026-05-01T04:23:00Z"));
        adapter.put(q, FIXED_NOW);

        // 한 시간 전 → 다른 슬롯
        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, FIXED_NOW.minus(Duration.ofHours(1)));

        assertThat(found).isEmpty();
    }

    @Test
    void 같은_슬롯_안의_두_시각은_같은_키로_저장되어_hit() {
        // 13:20 KST 슬롯의 시작
        Instant slotStart = Instant.parse("2026-05-01T04:20:00Z");
        // 같은 슬롯 내 다른 시각 (13:29:59 KST)
        Instant slotMiddle = Instant.parse("2026-05-01T04:29:59Z");

        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("100.00"), Currency.USD),
                slotStart);
        adapter.put(q, slotStart);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, slotMiddle);
        assertThat(found).isPresent();
        assertThat(found.get().price().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void 슬롯_경계를_넘으면_다른_키로_저장되어_miss() {
        // 13:29:59 KST 슬롯
        Instant before = Instant.parse("2026-05-01T04:29:59Z");
        // 13:30:00 KST 다음 슬롯
        Instant after = Instant.parse("2026-05-01T04:30:00Z");

        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("100.00"), Currency.USD),
                before);
        adapter.put(q, before);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, after);
        assertThat(found).isEmpty();
    }

    @Test
    void 같은_슬롯에_두번_put하면_마지막_값이_유지된다() {
        Quote first = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("100.00"), Currency.USD),
                Instant.parse("2026-05-01T04:23:00Z"));
        Quote second = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("200.00"), Currency.USD),
                Instant.parse("2026-05-01T04:24:00Z"));
        adapter.put(first, FIXED_NOW);
        adapter.put(second, FIXED_NOW);

        Optional<Quote> found = adapter.find("AAPL", Exchange.NAS, FIXED_NOW);

        assertThat(found).isPresent();
        assertThat(found.get().price().amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void TTL_속성은_저장시각에_1시간을_더한_epoch_second_Number다() {
        Quote q = new Quote("AAPL", Exchange.NAS,
                new Money(new BigDecimal("175.50"), Currency.USD),
                Instant.parse("2026-05-01T04:23:00Z"));
        adapter.put(q, FIXED_NOW);

        // raw item 직접 조회 — KST 13:20 슬롯
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TICKER#AAPL"),
                        "sk", AttributeValue.fromS(EXPECTED_SLOT_SK)))
                .build());
        Map<String, AttributeValue> item = response.item();

        assertThat(item).containsKey("ttl");
        AttributeValue ttlAttr = item.get("ttl");
        assertThat(ttlAttr.n()).isNotNull();

        long ttl = Long.parseLong(ttlAttr.n());
        long expected = FIXED_NOW.plus(Duration.ofHours(1)).getEpochSecond();
        assertThat(ttl).isEqualTo(expected);
    }

    @Test
    void exchange가_다르면_캐시_미스로_취급된다() {
        Quote nasQuote = new Quote("FOO", Exchange.NAS,
                new Money(new BigDecimal("50.00"), Currency.USD),
                Instant.parse("2026-05-01T04:23:00Z"));
        adapter.put(nasQuote, FIXED_NOW);

        // 동일 ticker, 동일 슬롯, 다른 exchange 로 조회
        Optional<Quote> found = adapter.find("FOO", Exchange.NYS, FIXED_NOW);

        assertThat(found).isEmpty();
    }

    @Test
    void 빈_캐시에서_find하면_빈_Optional() {
        Optional<Quote> found = adapter.find("UNKNOWN", Exchange.NAS, FIXED_NOW);
        assertThat(found).isEmpty();
    }
}
