package com.example.stockportfolio.adapter.persistence.dynamodb;

import com.example.stockportfolio.adapter.marketdata.kis.KisAccessTokenStore;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.TickerMetaRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.time.Clock;

@Configuration
public class DynamoDbConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${aws.dynamodb.endpoint:}") String endpoint,
            @Value("${aws.region:ap-northeast-2}") String region) {

        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder().build());

        if (endpoint != null && !endpoint.isBlank()) {
            // 로컬/테스트: endpoint override + 더미 자격증명
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        // 운영: default credential chain 사용
        return builder.build();
    }

    @Bean
    public PortfolioRepository portfolioRepository(
            DynamoDbClient client,
            @Value("${portfolio.table-name:Portfolio}") String tableName) {
        return new DynamoPortfolioRepository(client, tableName);
    }

    @Bean
    public TickerMetaRepository tickerMetaRepository(
            DynamoDbClient client,
            @Value("${portfolio.table-name:Portfolio}") String tableName) {
        return new DynamoTickerMetaRepository(client, tableName);
    }

    @Bean
    public KisAccessTokenStore kisAccessTokenStore(
            DynamoDbClient client,
            @Value("${portfolio.table-name:Portfolio}") String tableName,
            @Qualifier("kisClock") Clock clock) {
        return new DynamoKisAccessTokenStore(client, tableName, clock);
    }
}
