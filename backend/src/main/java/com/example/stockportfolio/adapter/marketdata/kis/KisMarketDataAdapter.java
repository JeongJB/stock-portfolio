package com.example.stockportfolio.adapter.marketdata.kis;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Quote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class KisMarketDataAdapter implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(KisMarketDataAdapter.class);

    // 한투 응답 명세가 공식 문서에 일관되게 노출되지 않아 후보 키를 우선순위대로 시도한다.
    private static final List<String> PRICE_KEYS = List.of("last");
    private static final List<String> FX_KEYS = List.of("t_rate");

    private static final Duration FX_TTL = Duration.ofHours(1);

    // 미국 주식 주간장 시간(평일 KST 10:00 포함 ~ 17:30 제외) 동안에는 정규장 EXCD 대신 BAY/BAQ/BAA 코드로 조회해야 시세가 잡힌다.
    // 토/일은 한국 주간장이 열리지 않아 시간대와 무관하게 정규장 EXCD 로 직접 조회한다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime DAY_SESSION_START = LocalTime.of(10, 0);
    private static final LocalTime DAY_SESSION_END = LocalTime.of(17, 30);

    private final KisHttpClient kisHttpClient;
    private final RestClient fxFallbackClient;
    private final String fxFallbackUrl;
    private final String fxProbeSymbol;
    private final Exchange fxProbeExchange;
    private final Clock clock;

    private final AtomicReference<CachedRate> fxCache = new AtomicReference<>();

    public KisMarketDataAdapter(KisHttpClient kisHttpClient,
                                RestClient fxFallbackClient,
                                String fxFallbackUrl,
                                String fxProbeSymbol,
                                Exchange fxProbeExchange,
                                Clock clock) {
        this.kisHttpClient = kisHttpClient;
        this.fxFallbackClient = fxFallbackClient;
        this.fxFallbackUrl = fxFallbackUrl;
        this.fxProbeSymbol = fxProbeSymbol;
        this.fxProbeExchange = fxProbeExchange;
        this.clock = clock;
    }

    @Override
    public Quote getQuote(String ticker, Exchange exchange) {
        String regularExcd = exchange.name();
        if (isDaySessionNow()) {
            String dayCode = dayExcd(exchange);
            try {
                return queryQuote(ticker, exchange, dayCode);
            } catch (IllegalStateException ex) {
                log.debug("주간장 코드 {} 미적중 → 정규장 {} 로 fallback (ticker={})", dayCode, regularExcd, ticker);
                return queryQuote(ticker, exchange, regularExcd);
            }
        }
        return queryQuote(ticker, exchange, regularExcd);
    }

    private Quote queryQuote(String ticker, Exchange exchange, String excd) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("AUTH", "");
        query.put("EXCD", excd);
        query.put("SYMB", ticker);

        JsonNode root = kisHttpClient.get("/uapi/overseas-price/v1/quotations/price", "HHDFS00000300", query);
        JsonNode output = output(root);
        BigDecimal price = readDecimal(output, PRICE_KEYS)
                .orElseThrow(() -> new IllegalStateException(
                        "KIS 시세 응답에서 가격 필드(" + PRICE_KEYS + ")를 찾을 수 없습니다 (output 키: "
                                + output.propertyNames() + ")"));
        Instant asOf = clock.instant();
        return new Quote(ticker, exchange, Money.of(price, Currency.USD), asOf);
    }

    private boolean isDaySessionNow() {
        ZonedDateTime nowKst = clock.instant().atZone(KST);
        DayOfWeek dow = nowKst.getDayOfWeek();
        // 토/일은 미국 정규장 직전 한국 주간장 자체가 열리지 않으므로 BAY/BAQ/BAA 매핑이 의미 없다.
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime nowTime = nowKst.toLocalTime();
        return !nowTime.isBefore(DAY_SESSION_START) && nowTime.isBefore(DAY_SESSION_END);
    }

    private static String dayExcd(Exchange exchange) {
        return switch (exchange) {
            case NAS -> "BAQ";
            case NYS -> "BAY";
            case AMS -> "BAA";
        };
    }

    @Override
    public BigDecimal getUsdKrwRate() {
        Instant now = clock.instant();
        CachedRate cached = fxCache.get();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.rate();
        }
        BigDecimal rate = fetchRateFromKis();
        if (rate == null) {
            log.warn("KIS 환율 추출 실패 → frankfurter.app 폴백 사용");
            rate = fetchRateFromFallback();
        }
        fxCache.set(new CachedRate(rate, now.plus(FX_TTL)));
        return rate;
    }

    private BigDecimal fetchRateFromKis() {
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("AUTH", "");
            query.put("EXCD", fxProbeExchange.name());
            query.put("SYMB", fxProbeSymbol);
            JsonNode root = kisHttpClient.get("/uapi/overseas-price/v1/quotations/price-detail",
                    "HHDFS76200200", query);
            JsonNode output = output(root);
            BigDecimal rate = readDecimal(output, FX_KEYS).orElse(null);
            if (rate == null) {
                if (log.isDebugEnabled()) {
                    log.debug("KIS price-detail 응답에서 환율 키({}) 매칭 실패. 응답 output 키 목록={}",
                            FX_KEYS, fieldNames(output));
                }
                return null;
            }
            if (rate.signum() <= 0
                    || rate.compareTo(new BigDecimal("100")) < 0
                    || rate.compareTo(new BigDecimal("5000")) > 0) {
                log.debug("KIS 환율 값이 정상 범위(100~5000) 밖이라 폐기: {}", rate);
                return null;
            }
            return rate;
        } catch (RuntimeException ex) {
            log.warn("KIS 환율 조회 중 예외", ex);
            return null;
        }
    }

    private BigDecimal fetchRateFromFallback() {
        JsonNode root = fxFallbackClient.get()
                .uri(fxFallbackUrl + "/latest?from=USD&to=KRW")
                .retrieve()
                .body(JsonNode.class);
        if (root == null || !root.has("rates") || !root.get("rates").has("KRW")) {
            // 응답 본문 전체를 메시지에 박지 않는다. 디버깅엔 최상위 키 목록만 충분.
            Iterable<String> keys = root != null ? root.propertyNames() : java.util.List.of();
            throw new IllegalStateException("FX 폴백 응답에서 rates.KRW 를 찾을 수 없습니다 (응답 키: " + keys + ")");
        }
        return new BigDecimal(root.get("rates").get("KRW").asString());
    }

    private static java.util.Collection<String> fieldNames(JsonNode node) {
        return node.propertyNames();
    }

    private static JsonNode output(JsonNode root) {
        if (root == null) {
            throw new IllegalStateException("KIS 응답이 비어있습니다");
        }
        if (!root.has("output")) {
            // 응답 본문 전체를 메시지에 박지 않는다. 키 목록만 노출.
            throw new IllegalStateException("KIS 응답에 output 노드가 없습니다 (응답 키: " + root.propertyNames() + ")");
        }
        return root.get("output");
    }

    private static java.util.Optional<BigDecimal> readDecimal(JsonNode output, List<String> keys) {
        for (String key : keys) {
            if (!output.has(key)) {
                continue;
            }
            String raw = output.get(key).asString();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                return java.util.Optional.of(new BigDecimal(raw.trim()));
            } catch (NumberFormatException ignore) {
                // 다음 후보 키로 진행
            }
        }
        return java.util.Optional.empty();
    }

    private record CachedRate(BigDecimal rate, Instant expiresAt) {
    }
}
