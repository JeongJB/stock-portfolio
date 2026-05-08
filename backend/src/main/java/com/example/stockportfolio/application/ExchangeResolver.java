package com.example.stockportfolio.application;

import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.TickerMetaRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ticker → 사용해야 할 {@link Exchange} 결정 + 시세 조회 결과로 META 카운터 자기치유.
 *
 * <ul>
 *   <li>META 가 없으면 NAS → NYS → AMS 순으로 탐색해 첫 성공 거래소를 META 에 박제 후 반환한다.</li>
 *   <li>META 가 있고 카운터 &lt; 3 이면 META.exchange 를 그대로 반환 (KIS 호출 0 회).</li>
 *   <li>META 가 있고 카운터 &ge; 3 이면 그 자리에서 NAS → NYS → AMS 재탐색 후 META 갱신.</li>
 *   <li>모든 거래소 실패 시 마지막 예외를 그대로 throw 하며, META 신규 케이스는 저장하지 않고
 *       기존 META 케이스는 그대로 유지한다 (재탐색 실패는 별도 onQuoteFailure 호출로 카운터가 누적됨).</li>
 * </ul>
 */
@Service
public class ExchangeResolver {

    private static final Logger log = LoggerFactory.getLogger(ExchangeResolver.class);

    /** 연속 실패가 이 임계 이상이 되면 다음 resolve() 시 재탐색을 강제한다. */
    static final int FAILURE_THRESHOLD = 3;

    /** 탐색 순서 — NAS(나스닥) → NYS(NYSE) → AMS(AMEX). */
    private static final List<Exchange> SEARCH_ORDER = List.of(Exchange.NAS, Exchange.NYS, Exchange.AMS);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MarketDataPort marketDataPort;
    private final TickerMetaRepository repository;
    private final Clock clock;

    public ExchangeResolver(MarketDataPort marketDataPort,
                            TickerMetaRepository repository,
                            Clock clock) {
        this.marketDataPort = Objects.requireNonNull(marketDataPort, "marketDataPort");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 사용해야 할 거래소를 반환한다. 부수효과로 META 가 새로 박제되거나 갱신될 수 있다.
     * 모든 거래소 탐색이 실패하면 마지막 예외를 throw 한다.
     */
    public Exchange resolve(String ticker) {
        return resolveWithMeta(ticker).exchange();
    }

    /**
     * {@link #resolve(String)} 와 동일하지만 결정에 사용된 META 자체를 반환한다.
     * 호출자가 sector 등 보조 필드를 함께 사용해야 할 때 GetItem 중복을 막는다.
     */
    public TickerMeta resolveWithMeta(String ticker) {
        Optional<TickerMeta> existing = repository.find(ticker);
        if (existing.isPresent() && existing.get().consecutiveQuoteFailures() < FAILURE_THRESHOLD) {
            return existing.get();
        }
        // META 미존재 또는 임계 이상 실패 — 탐색 수행. searchAndPersist 가 새 META 를 박제하므로 다시 find 가능.
        // 다만 추가 GetItem 을 피하기 위해 searchAndPersist 가 직접 META 를 반환하도록 한다.
        return searchAndPersistMeta(ticker, existing.orElse(null));
    }

    /**
     * 시세 조회 성공 직후 호출. 카운터 0 리셋 + 거래소·시각 갱신.
     * 같은 KST 날짜 내 카운터가 이미 0 인 경우 PUT 을 생략해 비용을 줄인다.
     */
    public void onQuoteSuccess(String ticker, Instant successAt) {
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(successAt, "successAt");
        Optional<TickerMeta> existing = repository.find(ticker);
        if (existing.isEmpty()) {
            // 정상 흐름에서는 resolve() 가 먼저 META 를 박제하므로 도달하지 않지만,
            // 안전하게 처리: 마지막에 사용한 거래소를 모르므로 NAS 로 보수적 박제.
            log.debug("onQuoteSuccess: META 없음 ticker={} → 탐색하지 않고 기록만 (NAS)", ticker);
            repository.save(new TickerMeta(ticker, Exchange.NAS, successAt, 0));
            return;
        }
        TickerMeta meta = existing.get();
        if (meta.consecutiveQuoteFailures() == 0
                && sameKstDay(meta.lastVerifiedAt(), successAt)) {
            return;
        }
        repository.save(meta.withSuccess(meta.exchange(), successAt));
    }

    /** 시세 조회 실패 직후 호출. 카운터 +1 (PUT 강제). META 가 없으면 무시. */
    public void onQuoteFailure(String ticker) {
        Objects.requireNonNull(ticker, "ticker");
        Optional<TickerMeta> existing = repository.find(ticker);
        if (existing.isEmpty()) {
            // META 가 없는 ticker 의 실패는 resolve() 단계의 탐색 실패로만 발생할 수 있어
            // 이 경로에서는 카운터를 누적할 대상이 없다. 무시.
            return;
        }
        repository.save(existing.get().withFailure());
    }

    /**
     * NAS → NYS → AMS 순으로 탐색해 첫 성공 거래소로 META 를 박제한다.
     * 기존 META 가 있으면 sector 등 보조 필드를 보존해야 하므로 호출자가 넘겨준다.
     */
    private TickerMeta searchAndPersistMeta(String ticker, TickerMeta existing) {
        RuntimeException lastError = null;
        for (Exchange exchange : SEARCH_ORDER) {
            try {
                marketDataPort.getQuote(ticker, exchange);
                Instant now = clock.instant();
                String preservedSector = existing != null ? existing.sector() : null;
                TickerMeta updated = new TickerMeta(ticker, exchange, now, 0, preservedSector);
                repository.save(updated);
                return updated;
            } catch (RuntimeException ex) {
                log.debug("거래소 탐색 시 시세 실패 ticker={} exchange={}: {}", ticker, exchange, ex.toString());
                lastError = ex;
            }
        }
        // 모두 실패: 마지막 예외 throw, META 변경 없음.
        throw lastError != null
                ? lastError
                : new IllegalStateException("거래소 탐색 실패 (원인 미상): " + ticker);
    }

    private static boolean sameKstDay(Instant a, Instant b) {
        return LocalDate.ofInstant(a, KST).equals(LocalDate.ofInstant(b, KST));
    }
}
