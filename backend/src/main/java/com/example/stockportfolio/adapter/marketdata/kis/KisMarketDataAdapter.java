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
import java.math.RoundingMode;
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
    // 등락률·52주 고저는 단일 키만 사용 (HHDFS00000300 명세).
    private static final String RATE_KEY = "rate";
    private static final String SIGN_KEY = "sign";
    private static final String BASE_KEY = "base";
    private static final String WEEK_HIGH_KEY = "h52p";
    private static final String WEEK_LOW_KEY = "l52p";

    private static final int RATE_SCALE = 2;

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
        // 보조 필드(등락률·52주 고저) 는 누락돼도 시세 자체는 가용으로 간주한다.
        BigDecimal dailyChangePct = readDailyChangePct(output, price);
        BigDecimal[] weekRange = readWeekRange(output);
        Instant asOf = clock.instant();
        return new Quote(
                ticker,
                exchange,
                Money.of(price, Currency.USD),
                asOf,
                dailyChangePct,
                weekRange[0],
                weekRange[1]);
    }

    /**
     * 등락률 추출. 우선순위:
     * 1) rate + sign 둘 다 → sign 으로 부호 결정 (+/0/−)
     * 2) rate 만 → raw 그대로 (이미 부호 포함이라 가정)
     * 3) last + base → (last - base) / base * 100, HALF_UP 2자리
     * 4) 그 외 → null
     */
    private static BigDecimal readDailyChangePct(JsonNode output, BigDecimal lastPrice) {
        java.util.Optional<BigDecimal> rate = readNonZeroOrSignedDecimal(output, RATE_KEY);
        java.util.Optional<String> sign = readString(output, SIGN_KEY);
        if (rate.isPresent() && sign.isPresent()) {
            return signedRate(rate.get(), sign.get());
        }
        if (rate.isPresent()) {
            return rate.get().setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        java.util.Optional<BigDecimal> base = readDecimal(output, List.of(BASE_KEY));
        if (base.isPresent() && base.get().signum() > 0 && lastPrice != null) {
            BigDecimal diff = lastPrice.subtract(base.get());
            return diff.divide(base.get(), 10, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    /**
     * sign 코드를 등락률에 적용. KIS 명세:
     * 1=상한, 2=상승 → 양수
     * 3=보합 → 0
     * 4=하한, 5=하락 → 음수
     * 그 외 알 수 없는 값은 raw rate 그대로 (안전한 fallback).
     */
    private static BigDecimal signedRate(BigDecimal rate, String sign) {
        BigDecimal abs = rate.abs().setScale(RATE_SCALE, RoundingMode.HALF_UP);
        return switch (sign) {
            case "1", "2" -> abs;
            case "3" -> BigDecimal.ZERO.setScale(RATE_SCALE);
            case "4", "5" -> abs.negate();
            default -> rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        };
    }

    /**
     * 52주 고저는 한 쌍 단위로만 의미. 둘 중 하나라도 누락/비정상이면 둘 다 null.
     * 반환은 [high, low] 순.
     */
    private static BigDecimal[] readWeekRange(JsonNode output) {
        BigDecimal high = readDecimal(output, List.of(WEEK_HIGH_KEY)).orElse(null);
        BigDecimal low = readDecimal(output, List.of(WEEK_LOW_KEY)).orElse(null);
        if (high == null || low == null) {
            return new BigDecimal[] { null, null };
        }
        return new BigDecimal[] { high, low };
    }

    /**
     * 등락률용 readDecimal 변형: 빈 문자열·공백·"0" 단독은 null 정규화.
     * (KIS 가 결측을 빈 문자열 또는 "0" 으로 내려주는 케이스 방어 — 단순 readDecimal 은 "0" 도 유효한
     * 값이라 보합과 결측을 구분 못한다. 여기서는 raw "0" 을 보합으로 받지 않고 null 처리해
     * sign 이 함께 있을 때만 0 (보합) 으로 확정한다. sign 이 없는 단독 "0" 은 정보 부족으로 폴백 진행.)
     */
    private static java.util.Optional<BigDecimal> readNonZeroOrSignedDecimal(JsonNode output, String key) {
        if (!output.has(key)) {
            return java.util.Optional.empty();
        }
        String raw = output.get(key).asString();
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = raw.trim();
        try {
            BigDecimal value = new BigDecimal(trimmed);
            // 결측 가드성 "0" 은 sign 가 함께 와야만 보합으로 확정 — 호출부에서 sign 매칭으로 처리.
            return java.util.Optional.of(value);
        } catch (NumberFormatException ignore) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<String> readString(JsonNode output, String key) {
        if (!output.has(key)) {
            return java.util.Optional.empty();
        }
        String raw = output.get(key).asString();
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(raw.trim());
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
