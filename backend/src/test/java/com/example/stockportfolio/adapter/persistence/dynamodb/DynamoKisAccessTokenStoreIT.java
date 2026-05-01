package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.marketdata.kis.KisAccessTokenStore;

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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoKisAccessTokenStoreIT {

    private static final String TABLE_NAME = "PortfolioKisTokenTest";

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.4"))
            .withExposedPorts(8000);

    private static DynamoDbClient client;
    private DynamoKisAccessTokenStore store;

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
        store = new DynamoKisAccessTokenStore(
                client, TABLE_NAME,
                Clock.fixed(Instant.parse("2026-04-28T00:00:00Z"), ZoneOffset.UTC));
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
        Instant expiresAt = Instant.parse("2026-04-29T00:00:00Z");
        store.save(new KisAccessTokenStore.StoredToken("ACCESS-1", expiresAt));

        Optional<KisAccessTokenStore.StoredToken> loaded = store.find();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().accessToken()).isEqualTo("ACCESS-1");
        assertThat(loaded.get().expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void find_미존재시_빈_Optional() {
        assertThat(store.find()).isEmpty();
    }

    @Test
    void save_는_upsert_동작한다() {
        Instant first = Instant.parse("2026-04-29T00:00:00Z");
        Instant second = Instant.parse("2026-04-30T00:00:00Z");
        store.save(new KisAccessTokenStore.StoredToken("OLD", first));
        store.save(new KisAccessTokenStore.StoredToken("NEW", second));

        KisAccessTokenStore.StoredToken loaded = store.find().orElseThrow();
        assertThat(loaded.accessToken()).isEqualTo("NEW");
        assertThat(loaded.expiresAt()).isEqualTo(second);
    }

    @Test
    void ttl_속성은_expiresAt의_epoch_second_Number다() {
        Instant expiresAt = Instant.parse("2026-04-29T00:00:00Z");
        store.save(new KisAccessTokenStore.StoredToken("ACCESS-1", expiresAt));

        Map<String, AttributeValue> item = client.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.fromS("META#kis"),
                        "sk", AttributeValue.fromS("ACCESS_TOKEN")))
                .build()).item();

        assertThat(item).containsKey("ttl");
        AttributeValue ttlAttr = item.get("ttl");
        assertThat(ttlAttr.n()).isNotNull();
        assertThat(Long.parseLong(ttlAttr.n())).isEqualTo(expiresAt.getEpochSecond());
        // expiresAt String round-trip 검증
        assertThat(item.get("expiresAt").s()).isEqualTo(expiresAt.toString());
        assertThat(item.get("accessToken").s()).isEqualTo("ACCESS-1");
    }
}
