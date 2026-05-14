package com.example.stockportfolio.adapter.lambda;

import com.example.stockportfolio.domain.MarketDataPort;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;

/**
 * SnapStart 의 init phase 에서 AWS SDK / KIS 호출 결과를 미리 채워 SnapStart snapshot 에 박는다.
 *
 * <p>{@link PostConstruct} (init phase) — Spring bean 초기화 시점. 외부 호출 결과 (SDK 내부 cache,
 * KisAccessTokenManager 토큰, SsmCredentialsProvider 자격증명, KisMarketDataAdapter.fxCache)
 * 가 SnapStart snapshot 에 박혀 restore 후 첫 invoke 가 cache hit 으로 즉시 응답.
 *
 * <p>{@code afterRestore} hook 은 의도적으로 사용하지 않는다 — invoke duration 만 측정하면 단축돼 보이지만,
 * 사용자 wall clock 체감 시간 = Restore Duration + Invoke Duration 이므로 afterRestore 가 길어지면
 * 오히려 손해. 측정 데이터: afterRestore priming 적용 시 Restore 5.3s + Invoke 1.6s = ~6.9s vs
 * init-only priming 시 Restore 0.5s + Invoke ~3.6s = ~4.1s. snapshot 박힘 효과로 충분하므로 afterRestore 불필요.
 *
 * <p>priming 대상:
 * <ul>
 *   <li><b>DDB describeTable</b> — DynamoDbClient 의 region/credential/endpoint resolve + UrlConnectionHttpClient
 *       의 lazy init trigger.
 *   <li><b>{@link MarketDataPort#getUsdKrwRate}</b> — KIS access token (DDB), SSM credentials, fxCache 모두 채움.
 *       첫 invoke 의 fxRate 가 0ms 캐시 hit. fxCache 유효 윈도우는 {@code KisMarketDataAdapter.FX_TTL} 참조.
 * </ul>
 */
@Component
public class SnapStartWarmup {

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
    }

    @PostConstruct
    public void warmup() {
        log.info("SnapStartWarmup: starting");

        long t0 = System.currentTimeMillis();
        try {
            ddb.describeTable(b -> b.tableName(tableName));
            log.info("SnapStartWarmup: DDB describeTable OK ({}ms)", System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.warn("SnapStartWarmup: DDB describeTable 실패 (무시): {}", ex.toString());
        }

        long t1 = System.currentTimeMillis();
        try {
            BigDecimal rate = marketDataPort.getUsdKrwRate();
            log.info("SnapStartWarmup: getUsdKrwRate OK rate={} ({}ms)",
                    rate, System.currentTimeMillis() - t1);
        } catch (Exception ex) {
            log.warn("SnapStartWarmup: getUsdKrwRate 실패 (무시): {}", ex.toString());
        }

        log.info("SnapStartWarmup: done (total {}ms)", System.currentTimeMillis() - t0);
    }
}
