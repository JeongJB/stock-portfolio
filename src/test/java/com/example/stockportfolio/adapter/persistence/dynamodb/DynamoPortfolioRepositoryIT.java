package com.example.stockportfolio.adapter.persistence.dynamodb;

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
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Instant;
import java.util.List;

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
