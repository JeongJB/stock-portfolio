package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.TickerMeta;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoTickerMetaRepositoryIT {

    private static final String TABLE_NAME = "PortfolioMetaTest";

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.4"))
            .withExposedPorts(8000);

    private static DynamoDbClient client;
    private DynamoTickerMetaRepository repository;

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
        repository = new DynamoTickerMetaRepository(client, TABLE_NAME);
    }

    @AfterEach
    void dropTable() {
        try {
            client.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
        } catch (ResourceNotFoundException ignored) {
        }
    }

    @Test
    void save_후_find하면_같은_값이_복원된다() {
        Instant verifiedAt = Instant.parse("2026-04-28T01:23:45Z");
        TickerMeta meta = new TickerMeta("AAPL", Exchange.NAS, verifiedAt, 0);

        repository.save(meta);

        Optional<TickerMeta> loaded = repository.find("AAPL");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().ticker()).isEqualTo("AAPL");
        assertThat(loaded.get().exchange()).isEqualTo(Exchange.NAS);
        assertThat(loaded.get().lastVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(loaded.get().consecutiveQuoteFailures()).isZero();
    }

    @Test
    void find_미존재_ticker는_빈_Optional() {
        assertThat(repository.find("UNKNOWN")).isEmpty();
    }

    @Test
    void save는_같은_ticker에_대해_upsert_동작한다() {
        Instant t0 = Instant.parse("2026-04-28T00:00:00Z");
        Instant t1 = Instant.parse("2026-04-29T00:00:00Z");
        repository.save(new TickerMeta("GEV", Exchange.NAS, t0, 0));
        repository.save(new TickerMeta("GEV", Exchange.NYS, t1, 5));

        TickerMeta loaded = repository.find("GEV").orElseThrow();
        assertThat(loaded.exchange()).isEqualTo(Exchange.NYS);
        assertThat(loaded.lastVerifiedAt()).isEqualTo(t1);
        assertThat(loaded.consecutiveQuoteFailures()).isEqualTo(5);
    }

    @Test
    void 카운터_정확하게_round_trip() {
        TickerMeta meta = new TickerMeta("FOO", Exchange.AMS,
                Instant.parse("2026-04-28T00:00:00Z"), 7);
        repository.save(meta);

        TickerMeta loaded = repository.find("FOO").orElseThrow();
        assertThat(loaded.consecutiveQuoteFailures()).isEqualTo(7);
        assertThat(loaded.exchange()).isEqualTo(Exchange.AMS);
    }
}
