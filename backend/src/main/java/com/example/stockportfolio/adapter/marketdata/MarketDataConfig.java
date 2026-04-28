package com.example.stockportfolio.adapter.marketdata;

import com.example.stockportfolio.adapter.marketdata.kis.KisMarketDataAdapter;
import com.example.stockportfolio.adapter.persistence.dynamodb.DynamoQuoteCacheAdapter;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.QuoteCachePort;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Clock;

@Configuration
public class MarketDataConfig {

    @Bean
    public QuoteCachePort quoteCachePort(
            DynamoDbClient client,
            @Value("${portfolio.table-name:Portfolio}") String tableName,
            @Qualifier("kisClock") Clock clock) {
        return new DynamoQuoteCacheAdapter(client, tableName, clock);
    }

    @Bean
    public MarketDataPort marketDataPort(
            KisMarketDataAdapter kisMarketDataAdapter,
            QuoteCachePort quoteCachePort,
            @Qualifier("kisClock") Clock clock) {
        return new CachingMarketDataAdapter(kisMarketDataAdapter, quoteCachePort, clock);
    }
}
