package com.example.stockportfolio.adapter.lambda;

import jakarta.annotation.PostConstruct;

import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * SnapStart 의 init phase 와 restore phase 양쪽에서 AWS SDK / JDK HttpClient 를 warmup 해
 * SnapStart cold first invoke 의 페널티를 최소화한다.
 *
 * <h2>2단계 priming</h2>
 *
 * <p>{@link PostConstruct} (init phase) — Spring bean 초기화 시점. AWS SDK 의 lazy init
 * (region resolve, credential chain, endpoint cache, http client 내부 상태) 을 trigger 하면
 * 그 결과가 SnapStart snapshot 에 박힌다.
 *
 * <p>{@link #afterRestore(Context)} (restore phase) — SnapStart 가 snapshot 으로부터 함수를 깨운 직후.
 * Snapshot 에는 OS-level socket / TLS connection 이 보존되지 않으므로 restore 후엔 모든 socket 이
 * 닫힌 상태. 여기서 DDB·KIS 에 dummy 호출을 1회 하면 TLS handshake / connection pool 채움이
 * <b>restore phase 내부에서</b> 일어나 사용자 invoke latency 에 잡히지 않는다 (CloudWatch 의
 * Restore Duration 만 늘어남).
 *
 * <h2>측정 근거</h2>
 *
 * <p>init phase priming 만 적용 후: cold first invoke 가 6.2초 → 3.6초로 단축.
 * 잔여 3.6초 의 ~2.9초는 load(1.3s) + fxRate(1.6s) 의 TLS handshake + 첫 round-trip 비용.
 * afterRestore priming 으로 이 socket 비용을 invoke 외부로 옮긴다.
 */
@Component
public class SnapStartWarmup implements Resource {

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
        // AWS Lambda SnapStart 가 restore 직후 Core.getGlobalContext() 의 등록된 Resource 의
        // afterRestore() 를 호출한다. 등록은 객체가 살아있는 동안만 유효 — singleton bean 이므로 안전.
        Core.getGlobalContext().register(this);
    }

    @PostConstruct
    public void warmupAtInit() {
        log.info("SnapStartWarmup[init]: starting");
        primeAll("init");
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        // snapshot 직전에 connection 을 닫는 cleanup 단계. JDK HttpClient / AWS SDK 의 socket 은
        // SnapStart 가 자동으로 정리하므로 별도 작업 불필요. no-op.
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        // restore 직후 — invoke latency 에 잡히지 않는 구간. socket / TLS handshake / KIS connection 을 미리 만든다.
        log.info("SnapStartWarmup[restore]: starting");
        primeAll("restore");
    }

    private void primeAll(String phase) {
        long t0 = System.currentTimeMillis();
        try {
            // DescribeTable 은 light read — DDB 와의 TCP/TLS + SDK 내부 캐시 채우기.
            ddb.describeTable(b -> b.tableName(tableName));
            log.info("SnapStartWarmup[{}]: DDB describeTable OK ({}ms)", phase, System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.warn("SnapStartWarmup[{}]: DDB describeTable 실패 (무시): {}", phase, ex.toString());
        }

        long t1 = System.currentTimeMillis();
        try {
            // KIS endpoint 에 HEAD 요청. 응답 코드는 무관 (404/405 도 OK) — TLS handshake + HttpClient
            // 내부 connection pool 채움이 목적.
            kisRestClient.head().uri(kisBaseUrl).retrieve().toBodilessEntity();
            log.info("SnapStartWarmup[{}]: KIS HEAD OK ({}ms)", phase, System.currentTimeMillis() - t1);
        } catch (Exception ex) {
            log.warn("SnapStartWarmup[{}]: KIS HEAD 실패 (무시, TLS handshake 는 발생): {}", phase, ex.toString());
        }

        log.info("SnapStartWarmup[{}]: done (total {}ms)", phase, System.currentTimeMillis() - t0);
    }
}
