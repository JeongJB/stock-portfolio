package com.example.stockportfolio.adapter.lambda;

import com.example.stockportfolio.domain.MarketDataPort;

import jakarta.annotation.PostConstruct;

import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;

/**
 * SnapStart 의 init phase 와 restore phase 양쪽에서 AWS SDK / JDK HttpClient / 도메인 캐시를 warmup 해
 * SnapStart cold first invoke 의 페널티를 최소화한다.
 *
 * <h2>2단계 priming</h2>
 *
 * <p>{@link PostConstruct} (init phase) — Spring bean 초기화 시점. 외부 호출 결과가 SnapStart snapshot 에
 * 박혀 restore 후 사용 가능.
 *
 * <p>{@link #afterRestore(Context)} (restore phase) — SnapStart 가 snapshot 으로부터 함수를 깨운 직후.
 * 이 시간은 사용자 invoke latency 에 잡히지 않고 CloudWatch 의 Restore Duration 에만 박힘.
 *
 * <h2>priming 대상</h2>
 *
 * <ol>
 *   <li><b>DDB describeTable</b> — DynamoDbClient 의 region/credential/endpoint resolve + UrlConnectionHttpClient
 *       의 lazy init trigger.
 *   <li><b>{@link MarketDataPort#getUsdKrwRate}</b> — 단순 KIS HEAD 보다 훨씬 효과 큰 priming.
 *     <ul>
 *       <li>DDB 에서 KIS access token fetch → {@code KisAccessTokenManager} in-memory cache 채움
 *       <li>SSM 에서 appkey/appsecret fetch → {@code SsmCredentialsProvider} cache 채움
 *       <li>KIS API 첫 호출 → KIS connection warmup
 *       <li>{@code KisMarketDataAdapter.fxCache} 채움 → 첫 invoke 의 fxRate 가 0ms 캐시 hit
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>이전 시도(KIS HEAD)는 토큰/자격증명/캐시를 채우지 않아 첫 invoke 의 fxRate 가 1.7초 그대로였다.
 */
@Component
public class SnapStartWarmup implements Resource {

    private static final Logger log = LoggerFactory.getLogger(SnapStartWarmup.class);

    private final DynamoDbClient ddb;
    private final MarketDataPort marketDataPort;
    private final String tableName;

    public SnapStartWarmup(DynamoDbClient ddb,
                           MarketDataPort marketDataPort,
                           @Value("${portfolio.table-name:Portfolio}") String tableName) {
        this.ddb = ddb;
        this.marketDataPort = marketDataPort;
        this.tableName = tableName;
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
        // snapshot 직전 cleanup 단계. JDK HttpClient / AWS SDK 의 socket 은 SnapStart 가
        // 자동 정리하므로 별도 작업 불필요. no-op.
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        log.info("SnapStartWarmup[restore]: starting");
        primeAll("restore");
    }

    private void primeAll(String phase) {
        long t0 = System.currentTimeMillis();
        try {
            ddb.describeTable(b -> b.tableName(tableName));
            log.info("SnapStartWarmup[{}]: DDB describeTable OK ({}ms)", phase, System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.warn("SnapStartWarmup[{}]: DDB describeTable 실패 (무시): {}", phase, ex.toString());
        }

        long t1 = System.currentTimeMillis();
        try {
            // 단순 connection warmup 이상의 효과: KIS access token, SSM credentials, fxCache 까지 한 번에 채움.
            // 첫 invoke 의 fxRate 호출이 캐시 hit (~0ms) 으로 즉시 응답.
            BigDecimal rate = marketDataPort.getUsdKrwRate();
            log.info("SnapStartWarmup[{}]: getUsdKrwRate OK rate={} ({}ms)",
                    phase, rate, System.currentTimeMillis() - t1);
        } catch (Exception ex) {
            log.warn("SnapStartWarmup[{}]: getUsdKrwRate 실패 (무시): {}", phase, ex.toString());
        }

        log.info("SnapStartWarmup[{}]: done (total {}ms)", phase, System.currentTimeMillis() - t0);
    }
}
