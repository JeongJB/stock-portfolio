package com.example.stockportfolio.adapter.marketdata.kis;

import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.http.HttpClient;
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
        // JDK HttpClient 는 connection pool + keep-alive + HTTP/2 를 기본 지원한다.
        // 직전에 쓰던 SimpleClientHttpRequestFactory 는 HttpURLConnection 기반이라
        // 매 호출마다 TLS handshake 가 발생 — SnapStart restore 후 첫 view() 의 8개
        // 종목 병렬 시세 호출이 8번 TLS handshake 를 동시 수행해 6초 가까이 걸렸다.
        // HttpClient 인스턴스는 thread-safe + 재사용 안전. RestClient 와 함께 싱글톤 빈으로 공유.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_2)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public Clock kisClock() {
        return Clock.systemUTC();
    }

    @Bean
    public KisCredentialsProvider kisCredentialsProvider(
            SsmClient ssmClient,
            @Value("${kis.appkey-parameter:/portfolio/kis/app-key}") String appKeyParameter,
            @Value("${kis.appsecret-parameter:/portfolio/kis/app-secret}") String appSecretParameter) {
        return new SsmCredentialsProvider(ssmClient, appKeyParameter, appSecretParameter);
    }

    @Bean
    public KisAccessTokenManager kisAccessTokenManager(
            RestClient kisRestClient,
            @Value("${kis.base-url:https://openapi.koreainvestment.com:9443}") String baseUrl,
            KisCredentialsProvider credentialsProvider,
            Clock kisClock,
            KisAccessTokenStore kisAccessTokenStore) {
        return new KisAccessTokenManager(kisRestClient, baseUrl, credentialsProvider, kisClock, kisAccessTokenStore);
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
            @Value("${fx.fallback-url:https://api.frankfurter.app}") String fxFallbackUrl,
            @Value("${kis.fx-probe-symbol:AAPL}") String fxProbeSymbol,
            @Value("${kis.fx-probe-exchange:NAS}") String fxProbeExchange,
            Clock kisClock) {
        return new KisMarketDataAdapter(
                kisHttpClient,
                kisRestClient,
                fxFallbackUrl,
                fxProbeSymbol,
                Exchange.valueOf(fxProbeExchange),
                kisClock);
    }
}
