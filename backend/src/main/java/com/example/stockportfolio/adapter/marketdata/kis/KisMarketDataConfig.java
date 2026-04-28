package com.example.stockportfolio.adapter.marketdata.kis;

import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class KisMarketDataConfig {

    @Bean
    public SsmClient ssmClient(@Value("${aws.region:ap-northeast-2}") String region) {
        return SsmClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @Bean
    public RestClient kisRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public Clock kisClock() {
        return Clock.systemUTC();
    }

    @Bean
    public KisCredentialsProvider kisCredentialsProvider(
            SsmClient ssmClient,
            @Value("${kis.appkey-parameter:/stockportfolio/api/appkey}") String appKeyParameter,
            @Value("${kis.appsecret-parameter:/stockportfolio/api/appsecret}") String appSecretParameter) {
        return new SsmCredentialsProvider(ssmClient, appKeyParameter, appSecretParameter);
    }

    @Bean
    public KisAccessTokenManager kisAccessTokenManager(
            RestClient kisRestClient,
            @Value("${kis.base-url:https://openapi.koreainvestment.com:9443}") String baseUrl,
            KisCredentialsProvider credentialsProvider,
            Clock kisClock) {
        return new KisAccessTokenManager(kisRestClient, baseUrl, credentialsProvider, kisClock);
    }

    @Bean
    public KisHttpClient kisHttpClient(
            RestClient kisRestClient,
            @Value("${kis.base-url:https://openapi.koreainvestment.com:9443}") String baseUrl,
            KisAccessTokenManager tokenManager,
            KisCredentialsProvider credentialsProvider) {
        return new KisHttpClient(kisRestClient, baseUrl, tokenManager, credentialsProvider);
    }

    @Bean
    public KisMarketDataAdapter kisMarketDataAdapter(
            KisHttpClient kisHttpClient,
            RestClient kisRestClient,
            @Value("${exchangerate.host-url:https://api.exchangerate.host}") String exchangeRateHostUrl,
            @Value("${kis.fx-probe-symbol:AAPL}") String fxProbeSymbol,
            @Value("${kis.fx-probe-exchange:NAS}") String fxProbeExchange,
            Clock kisClock) {
        return new KisMarketDataAdapter(
                kisHttpClient,
                kisRestClient,
                exchangeRateHostUrl,
                fxProbeSymbol,
                Exchange.valueOf(fxProbeExchange),
                kisClock);
    }
}
