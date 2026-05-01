package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.web.dto.PositionView;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quantity;
import com.example.stockportfolio.domain.Trade;

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
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DynamoPortfolioRepositoryIT {

    private static final String TABLE_NAME = "PortfolioTest";

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.4"))
            .withExposedPorts(8000);

    private static DynamoDbClient client;
    private PortfolioRepository repository;

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
        // 운영 SAM template 와 동일하게 GSI1 (gsi1pk/gsi1sk) 도 함께 프로비저닝.
        client.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("gsi1pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("gsi1sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("GSI1")
                        .keySchema(
                                KeySchemaElement.builder().attributeName("gsi1pk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("gsi1sk").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build());
        repository = new DynamoPortfolioRepository(client, TABLE_NAME);
    }

    @AfterEach
    void dropTable() {
        try {
            client.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
        } catch (ResourceNotFoundException ignored) {
            // 테스트 격리: 이미 없으면 무시
        }
    }

    @Test
    void load_빈_상태에서는_빈_Portfolio를_반환한다() {
        Portfolio loaded = repository.load();

        assertThat(loaded.cashUsd()).isEqualTo(Money.zero(Currency.USD));
        assertThat(loaded.positions()).isEmpty();
        assertThat(loaded.principal()).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void DEPOSIT을_record하면_cash와_cumulativeDeposit이_반영된다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("1000", Currency.USD));
        portfolio.apply(deposit);

        repository.recordTrade(deposit, portfolio);

        Portfolio loaded = repository.load();
        assertThat(loaded.cashUsd()).isEqualTo(Money.of("1000", Currency.USD));
        assertThat(loaded.cumulativeDeposit()).isEqualTo(Money.of("1000", Currency.USD));
        assertThat(loaded.cumulativeWithdraw()).isEqualTo(Money.zero(Currency.USD));
        assertThat(loaded.principal()).isEqualTo(Money.of("1000", Currency.USD));
    }

    @Test
    void BUY를_record하면_position과_cash가_저장된다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("10000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        Trade buy = Trade.buy(Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", Quantity.of("10"),
                Money.of("150", Currency.USD), Money.of("1", Currency.USD));
        portfolio.apply(buy);
        repository.recordTrade(buy, portfolio);

        Portfolio loaded = repository.load();
        // 10000 - (150*10 + 1) = 8499
        assertThat(loaded.cashUsd()).isEqualTo(Money.of("8499", Currency.USD));
        assertThat(loaded.positions()).containsKey("AAPL");
        Position aapl = loaded.position("AAPL").orElseThrow();
        assertThat(aapl.qty()).isEqualTo(Quantity.of("10"));
        assertThat(aapl.avgCost()).isEqualTo(Money.of("150", Currency.USD));
    }

    @Test
    void SELL로_수량이_0이_되면_position이_삭제된다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("10000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        Trade buy = Trade.buy(Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", Quantity.of("10"),
                Money.of("150", Currency.USD), Money.zero(Currency.USD));
        portfolio.apply(buy);
        repository.recordTrade(buy, portfolio);

        Trade sell = Trade.sell(Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", Quantity.of("10"),
                Money.of("160", Currency.USD), Money.zero(Currency.USD));
        portfolio.apply(sell);
        repository.recordTrade(sell, portfolio);

        Portfolio loaded = repository.load();
        assertThat(loaded.positions()).doesNotContainKey("AAPL");
        // 10000 - 1500 + 1600 = 10100
        assertThat(loaded.cashUsd()).isEqualTo(Money.of("10100", Currency.USD));
    }

    @Test
    void 동일_Trade_id로_중복_record하면_DomainException을_던진다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("1000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        // 동일한 trade를 그대로 한 번 더 — 조건부 Put 실패 → 트랜잭션 취소
        assertThatThrownBy(() -> repository.recordTrade(deposit, portfolio))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void saveSnapshot_단건_저장_후_조회된다() {
        SnapshotView snapshot = sampleSnapshot("2026-04-28");
        repository.saveSnapshot(snapshot);

        List<SnapshotView> found = repository.findSnapshots(
                LocalDate.parse("2026-04-28"), LocalDate.parse("2026-04-28"));
        assertThat(found).hasSize(1);
        SnapshotView loaded = found.get(0);
        assertThat(loaded.date()).isEqualTo(LocalDate.parse("2026-04-28"));
        assertThat(loaded.usdKrwRate()).isEqualByComparingTo(new BigDecimal("1400"));
        assertThat(loaded.totalMarketValueUsd()).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(loaded.positions()).hasSize(1);
        assertThat(loaded.positions().get(0).ticker()).isEqualTo("AAPL");
        assertThat(loaded.positions().get(0).qty()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void findSnapshots_여러_날짜는_오름차순_시계열로_반환된다() {
        // 일부러 역순으로 저장
        repository.saveSnapshot(sampleSnapshot("2026-04-28"));
        repository.saveSnapshot(sampleSnapshot("2026-04-20"));
        repository.saveSnapshot(sampleSnapshot("2026-04-25"));
        // 윈도 밖
        repository.saveSnapshot(sampleSnapshot("2026-05-01"));

        List<SnapshotView> found = repository.findSnapshots(
                LocalDate.parse("2026-04-20"), LocalDate.parse("2026-04-28"));

        assertThat(found).hasSize(3);
        assertThat(found.get(0).date()).isEqualTo(LocalDate.parse("2026-04-20"));
        assertThat(found.get(1).date()).isEqualTo(LocalDate.parse("2026-04-25"));
        assertThat(found.get(2).date()).isEqualTo(LocalDate.parse("2026-04-28"));
    }

    @Test
    void saveSnapshot_같은_날짜는_덮어쓴다() {
        repository.saveSnapshot(sampleSnapshot("2026-04-28", "1000"));
        repository.saveSnapshot(sampleSnapshot("2026-04-28", "9999"));

        List<SnapshotView> found = repository.findSnapshots(
                LocalDate.parse("2026-04-28"), LocalDate.parse("2026-04-28"));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).cashUsd()).isEqualByComparingTo(new BigDecimal("9999"));
    }

    private static SnapshotView sampleSnapshot(String date) {
        return sampleSnapshot(date, "500");
    }

    private static SnapshotView sampleSnapshot(String date, String cashUsd) {
        BigDecimal cash = new BigDecimal(cashUsd);
        return new SnapshotView(
                LocalDate.parse(date),
                OffsetDateTime.parse(date + "T09:00:00+09:00"),
                new BigDecimal("1400"),
                cash,
                cash.multiply(new BigDecimal("1400")),
                new BigDecimal("1500.0000"),
                new BigDecimal("2100000.0000"),
                new BigDecimal("2000.0000"),
                new BigDecimal("2800000.0000"),
                new BigDecimal("1000.0000"),
                new BigDecimal("1400000.0000"),
                new BigDecimal("1000.0000"),
                new BigDecimal("1400000.0000"),
                List.of(new PositionView(
                        "AAPL",
                        new BigDecimal("10"),
                        new BigDecimal("100.0000"),
                        new BigDecimal("140000.0000"),
                        new BigDecimal("0.0000"),
                        new BigDecimal("200.0000"),
                        new BigDecimal("280000.0000"),
                        new BigDecimal("2000.0000"),
                        new BigDecimal("2800000.0000"),
                        new BigDecimal("0.800000"),
                        new BigDecimal("1000.0000"),
                        new BigDecimal("1400000.0000"))));
    }

    @Test
    void BUY_거래는_GSI1_키가_박제된다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("10000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        Trade buy = Trade.buy(Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", Quantity.of("10"),
                Money.of("150", Currency.USD), Money.zero(Currency.USD));
        portfolio.apply(buy);
        repository.recordTrade(buy, portfolio);

        // base 테이블에서 직접 raw item 조회 → gsi1pk/gsi1sk 가 박혀 있어야 함
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.fromS("USER#me"),
                        "sk", AttributeValue.fromS("TRADE#" + buy.executedAt() + "#" + buy.id())))
                .build());
        Map<String, AttributeValue> item = response.item();
        assertThat(item).containsKey("gsi1pk");
        assertThat(item).containsKey("gsi1sk");
        assertThat(item.get("gsi1pk").s()).isEqualTo("TICKER#AAPL");
        assertThat(item.get("gsi1sk").s()).isEqualTo("TRADE#" + buy.executedAt() + "#" + buy.id());
    }

    @Test
    void DEPOSIT_거래는_GSI1_키가_없다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("1000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.fromS("USER#me"),
                        "sk", AttributeValue.fromS("TRADE#" + deposit.executedAt() + "#" + deposit.id())))
                .build());
        Map<String, AttributeValue> item = response.item();
        assertThat(item).doesNotContainKey("gsi1pk");
        assertThat(item).doesNotContainKey("gsi1sk");
    }

    @Test
    void listTradesByTicker는_BUY_SELL만_최신순으로_반환한다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("100000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        Trade buyAapl = Trade.buy(Instant.parse("2026-01-02T00:00:00Z"),
                "AAPL", Quantity.of("10"),
                Money.of("150", Currency.USD), Money.zero(Currency.USD));
        portfolio.apply(buyAapl);
        repository.recordTrade(buyAapl, portfolio);

        Trade buyMsft = Trade.buy(Instant.parse("2026-01-02T12:00:00Z"),
                "MSFT", Quantity.of("5"),
                Money.of("400", Currency.USD), Money.zero(Currency.USD));
        portfolio.apply(buyMsft);
        repository.recordTrade(buyMsft, portfolio);

        Trade sellAapl = Trade.sell(Instant.parse("2026-01-03T00:00:00Z"),
                "AAPL", Quantity.of("5"),
                Money.of("160", Currency.USD), Money.zero(Currency.USD));
        portfolio.apply(sellAapl);
        repository.recordTrade(sellAapl, portfolio);

        List<Trade> trades = repository.listTradesByTicker("AAPL", 10);

        assertThat(trades).hasSize(2);
        // 최신순: SELL(2026-01-03) → BUY(2026-01-02)
        assertThat(trades.get(0).id()).isEqualTo(sellAapl.id());
        assertThat(trades.get(1).id()).isEqualTo(buyAapl.id());
    }

    @Test
    void listTradesByTicker_DEPOSIT은_제외된다() {
        Portfolio portfolio = new Portfolio();
        Trade deposit = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("1000", Currency.USD));
        portfolio.apply(deposit);
        repository.recordTrade(deposit, portfolio);

        // DEPOSIT 만 있을 때 임의 ticker 로 조회 → 빈 결과
        List<Trade> trades = repository.listTradesByTicker("AAPL", 10);
        assertThat(trades).isEmpty();
    }

    @Test
    void listRecentTrades는_최신순으로_limit개를_반환한다() {
        Portfolio portfolio = new Portfolio();
        Trade t1 = Trade.deposit(Instant.parse("2026-01-01T00:00:00Z"),
                Money.of("1000", Currency.USD));
        portfolio.apply(t1);
        repository.recordTrade(t1, portfolio);

        Trade t2 = Trade.deposit(Instant.parse("2026-01-02T00:00:00Z"),
                Money.of("500", Currency.USD));
        portfolio.apply(t2);
        repository.recordTrade(t2, portfolio);

        Trade t3 = Trade.deposit(Instant.parse("2026-01-03T00:00:00Z"),
                Money.of("200", Currency.USD));
        portfolio.apply(t3);
        repository.recordTrade(t3, portfolio);

        List<Trade> recent = repository.listRecentTrades(2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).id()).isEqualTo(t3.id());
        assertThat(recent.get(1).id()).isEqualTo(t2.id());
    }
}
