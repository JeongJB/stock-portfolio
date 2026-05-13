package com.example.stockportfolio.adapter.lambda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * SnapStart init phase 에서 AWS SDK 와 JDK HttpClient 의 lazy init 을 미리 trigger 해
 * SnapStart snapshot 에 SDK 내부 cache (region / credential chain / endpoint / HttpClient 내부 상태)
 * 를 박는다. restore 후 첫 호출의 lazy init 비용이 사라져 cold first invoke 가 6초 → 1초 이하로 단축.
 *
 * <p>측정 근거: timing 로그로 view() 분해 시 SnapStart restore 후 첫 호출이
 * load=3004ms / fxRate=2475ms / quotes(8 parallel)=757ms 였다. load 와 fxRate 의 첫 호출 페널티가
 * 두 SDK 의 lazy init 1회 비용. 같은 instance 의 두 번째 호출은 listTrades=40ms 처럼 정상.
 *
 * <p>OS socket / TLS connection 자체는 snapshot 에 안 박히고 restore 시 닫힌다 (AWS 공식 동작).
 * 따라서 첫 호출에 TLS handshake 는 여전히 발생하지만, SDK 내부 lazy init (region resolution,
 * credential chain, endpoint resolve, HttpClient 내부 상태) 이 사라지면 ~3초 페널티가 ~수백 ms 로 줄어든다.
 *
 * <p>{@link ApplicationReadyEvent} 가 SpringApplication.run() 완료 직후 발생하므로 Lambda 의 init phase
 * 내부에서 실행됨이 보장된다 — 결과가 snapshot 에 박힌다.
 *
 * <p>로컬/테스트 환경에선 KIS HEAD 가 외부 호출이라 거슬리고 의미도 없으므로 Lambda 환경에서만 실행한다.
 */
@Component
public class SnapStartWarmup {

    private static final Logger log = LoggerFactory.getLogger(SnapStartWarmup.class);

    private final DynamoDbClient ddb;
    private final RestClient kisRestClient;
    private final String tableName;
    private final String kisBaseUrl;

    public SnapStartWarmup(DynamoDbClient ddb,
                           RestClient kisRestClient,
                           @Value("${portfolio.table-name:Portfolio}") String tableName,
                           @Value("${kis.base-url:https://openapi.koreainvestment.com:9443}") String kisBaseUrl) {
        this.ddb = ddb;
        this.kisRestClient = kisRestClient;
        this.tableName = tableName;
        this.kisBaseUrl = kisBaseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        // Lambda 외 환경(로컬 dev, 테스트)에선 외부 호출이 의미 없거나 거슬리므로 skip.
        if (System.getenv("AWS_LAMBDA_FUNCTION_NAME") == null) {
            return;
        }

        long t0 = System.currentTimeMillis();
        try {
            // DescribeTable 은 light read — SDK 의 region/credential/endpoint resolve 와
            // UrlConnectionHttpClient 의 lazy init 을 전부 trigger 한다.
            ddb.describeTable(b -> b.tableName(tableName));
            log.info("SnapStart warmup: DDB describeTable OK ({}ms)", System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.warn("SnapStart warmup: DDB describeTable 실패 (무시): {}", ex.toString());
        }

        long t1 = System.currentTimeMillis();
        try {
            // KIS endpoint 에 HEAD 요청 — 응답 코드는 무관 (404/405 가 와도 OK).
            // JDK HttpClient 의 내부 lazy init + TLS 컨텍스트 / ALPN 협상 캐시를 trigger 하는 게 목적.
            kisRestClient.head().uri(kisBaseUrl).retrieve().toBodilessEntity();
            log.info("SnapStart warmup: KIS HEAD OK ({}ms)", System.currentTimeMillis() - t1);
        } catch (Exception ex) {
            // HEAD 가 4xx 라도 TLS handshake + HttpClient init 은 일어났으므로 효과 있음.
            log.warn("SnapStart warmup: KIS HEAD 실패 (무시, HttpClient init 은 발생): {}", ex.toString());
        }
    }
}
