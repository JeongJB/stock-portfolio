package com.example.stockportfolio.adapter.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.stockportfolio.adapter.web.PortfolioController;
import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.RecordTradeRequest;
import com.example.stockportfolio.adapter.web.dto.RecordTradeResponse;
import com.example.stockportfolio.adapter.web.dto.SnapshotListResponse;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.application.PortfolioApplicationService;
import com.example.stockportfolio.application.SectorValidator;
import com.example.stockportfolio.application.TradeReplayValidationException;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Trade;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;

/**
 * SCF FunctionInvoker가 디스커버하는 단일 라우터 함수.
 *
 * P0-3 범위에서는 컴파일 + Spring 컨텍스트 부팅 검증이 핵심이므로 라우팅은 최소만 구현한다.
 * 본격적인 API Gateway → Spring MVC 라우팅은 후속 작업에서 spring-cloud-function-serverless-web
 * 의 ServerlessMVC를 통해 컨트롤러를 그대로 노출하는 방향으로 확장한다.
 *
 * Lambda 핸들러: org.springframework.cloud.function.adapter.aws.FunctionInvoker
 * Function 이름(spring.cloud.function.definition): apiGatewayHandler
 */
@Configuration
public class LambdaConfig {

    private static final Logger log = LoggerFactory.getLogger(LambdaConfig.class);

    // CloudFront 가 origin custom header 로 주입하는 공유 시크릿. 운영 Lambda 는 SAM 이 항상
    // ORIGIN_VERIFY_SECRET 환경변수를 주입한다. 로컬 단위 테스트(JUnit) 에서는 미설정이라
    // verifyOrigin 이 fail-secure 분기로 들어가지 않도록 RUNNING_IN_LAMBDA 가드를 둔다.
    private static final String ORIGIN_VERIFY_SECRET = resolveOriginVerifySecret();
    // AWS Lambda 런타임은 항상 AWS_LAMBDA_FUNCTION_NAME 을 자동 주입한다 — 운영 환경 식별자.
    private static final boolean RUNNING_IN_LAMBDA = System.getenv("AWS_LAMBDA_FUNCTION_NAME") != null;

    private static String resolveOriginVerifySecret() {
        String env = System.getenv("ORIGIN_VERIFY_SECRET");
        return (env == null || env.isBlank()) ? null : env;
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> apiGatewayHandler(
            PortfolioApplicationService service,
            ObjectMapper mapper) {

        return event -> {
            try {
                String method = event.getHttpMethod() != null ? event.getHttpMethod() : "GET";
                String path = event.getPath() != null ? event.getPath() : "";

                // CloudFront 경유 검증 — origin custom header X-Origin-Verify 가
                // ORIGIN_VERIFY_SECRET 과 일치하지 않으면 즉시 403. API key 가 leak 돼도
                // CloudFront 우회 직접 호출은 여기서 차단된다.
                if (!verifyOrigin(event)) {
                    return forbidden();
                }

                if ("GET".equalsIgnoreCase(method) && path.endsWith("/api/portfolio")) {
                    PortfolioView view = service.view();
                    return ok(mapper.writeValueAsString(view));
                }
                if ("GET".equalsIgnoreCase(method) && path.endsWith("/api/trades")) {
                    String limitStr = event.getQueryStringParameters() != null
                            ? event.getQueryStringParameters().getOrDefault("limit", "50")
                            : "50";
                    int limit = Integer.parseInt(limitStr);
                    return ok(mapper.writeValueAsString(service.recentTrades(limit)));
                }
                if ("POST".equalsIgnoreCase(method) && path.endsWith("/api/trades")) {
                    RecordTradeRequest req = mapper.readValue(
                            event.getBody() != null ? event.getBody() : "{}",
                            RecordTradeRequest.class);
                    Trade trade = service.recordTrade(req.toCommand());
                    return created(mapper.writeValueAsString(RecordTradeResponse.from(trade)));
                }
                if ("POST".equalsIgnoreCase(method) && path.endsWith("/api/snapshots")) {
                    SnapshotView snapshot = service.takeSnapshot();
                    return ok(mapper.writeValueAsString(snapshot));
                }
                if ("GET".equalsIgnoreCase(method) && path.endsWith("/api/snapshots")) {
                    Map<String, String> qs = event.getQueryStringParameters();
                    LocalDate from = parseDateOrNull(qs, "from");
                    LocalDate to = parseDateOrNull(qs, "to");
                    return ok(mapper.writeValueAsString(
                            new SnapshotListResponse(service.listSnapshots(from, to))));
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    String tradeId = extractTradeIdOrNull(path);
                    if (tradeId != null) {
                        service.deleteTrade(tradeId);
                        return ok(mapper.writeValueAsString(service.view()));
                    }
                }
                if ("PATCH".equalsIgnoreCase(method)) {
                    // PATCH /api/positions/{ticker}/sector — Spring MVC 컨트롤러와 동일 정책.
                    // 라우팅이 두 곳(여기 + PortfolioController)에 중복 존재 — 새 endpoint 추가 시
                    // 둘 다 손대지 않으면 Lambda 환경에서 404 가 난다 (line 33-35 주석의 ServerlessMVC
                    // 통합이 이뤄지면 해소될 burden).
                    String ticker = extractSectorTickerOrNull(path);
                    if (ticker != null) {
                        PortfolioController.UpdateSectorRequest req = mapper.readValue(
                                event.getBody() != null ? event.getBody() : "{}",
                                PortfolioController.UpdateSectorRequest.class);
                        String normalized;
                        try {
                            normalized = SectorValidator.normalize(req == null ? null : req.sector());
                        } catch (IllegalArgumentException ex) {
                            return validationFailed(ex.getMessage());
                        }
                        String upperTicker = ticker.toUpperCase(java.util.Locale.ROOT);
                        String saved = service.updateSector(upperTicker, normalized);
                        return ok(mapper.writeValueAsString(
                                new PortfolioController.UpdateSectorResponse(upperTicker, saved)));
                    }
                }

                return notFound(path);
            } catch (NoSuchElementException ex) {
                return notFoundBody(ex.getMessage());
            } catch (TradeReplayValidationException ex) {
                return validationFailed(ex.getMessage());
            } catch (DomainException ex) {
                return badRequest(ex.getMessage());
            } catch (Exception ex) {
                return serverError(ex);
            }
        };
    }

    /**
     * EventBridge Scheduler 가 매일 KST 23:30 에 호출하는 자동 스냅샷 함수.
     * 입력 페이로드는 사용하지 않는다 (Scheduler payload 가 비어 있어도 SCF 가 `{}` 로 전달).
     * 실패 시 예외를 전파해 Lambda Errors 메트릭이 +1 되도록 두면 SNS 알람이 작동한다.
     */
    @Bean
    public Function<Map<String, Object>, Map<String, Object>> scheduledSnapshotHandler(
            PortfolioApplicationService service) {

        return event -> {
            log.info("scheduled snapshot trigger received");
            SnapshotView snapshot = service.takeSnapshot();
            log.info("scheduled snapshot saved: date={} totalAssetsUsd={}",
                    snapshot.date(), snapshot.totalAssetsUsd());
            return Map.of(
                    "status", "ok",
                    "date", snapshot.date().toString());
        };
    }

    /**
     * CloudFront origin custom header (X-Origin-Verify) 와 ORIGIN_VERIFY_SECRET 비교.
     *
     * <p>fail-secure 정책: 운영 Lambda(AWS_LAMBDA_FUNCTION_NAME 박혀 있음) 에서 secret 이
     * 비어 있으면 설정 오류로 간주하고 모든 요청 거부. 로컬 단위 테스트(JUnit) 에서는
     * env var 가 자연스럽게 미설정이므로 통과시켜야 한다.
     *
     * <p>비교는 {@link MessageDigest#isEqual(byte[], byte[])} 로 상수 시간에 수행해 timing
     * side-channel 가능성을 차단한다. 네트워크 latency 가 압도적이라 실측 위험은 매우 낮지만
     * best practice.
     *
     * <p>APIGatewayProxyRequestEvent.getHeaders() 는 헤더명을 그대로 보존하므로
     * case-insensitive 매치 필요.
     */
    private static boolean verifyOrigin(APIGatewayProxyRequestEvent event) {
        if (ORIGIN_VERIFY_SECRET == null) {
            // 운영(Lambda) 에서 secret 누락은 설정 오류 — fail-secure.
            // 로컬 테스트 환경에선 AWS_LAMBDA_FUNCTION_NAME 미설정이라 통과.
            return !RUNNING_IN_LAMBDA;
        }
        Map<String, String> headers = event.getHeaders();
        if (headers == null) {
            return false;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("X-Origin-Verify")) {
                String value = e.getValue();
                if (value == null) {
                    return false;
                }
                return MessageDigest.isEqual(
                        ORIGIN_VERIFY_SECRET.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private static APIGatewayProxyResponseEvent forbidden() {
        return response(403, "{\"error\":\"forbidden\"}");
    }

    private static APIGatewayProxyResponseEvent ok(String body) {
        return response(200, body);
    }

    private static APIGatewayProxyResponseEvent created(String body) {
        return response(201, body);
    }

    private static APIGatewayProxyResponseEvent notFound(String path) {
        return response(404, "{\"error\":\"not_found\",\"path\":\"" + path + "\"}");
    }

    private static APIGatewayProxyResponseEvent notFoundBody(String message) {
        return response(404, "{\"error\":\"not_found\",\"message\":" + quote(message) + "}");
    }

    private static APIGatewayProxyResponseEvent validationFailed(String message) {
        return response(422, "{\"error\":\"validation_failed\",\"message\":" + quote(message) + "}");
    }

    private static APIGatewayProxyResponseEvent badRequest(String message) {
        return response(400, "{\"error\":\"domain_error\",\"message\":" + quote(message) + "}");
    }

    /**
     * /api/trades/{id} 패턴에서 id 만 뽑는다. 다른 경로면 null.
     * URL 인코딩된 id (UUID 는 인코딩 영향 없음이지만 일반화) 도 디코드.
     */
    private static String extractTradeIdOrNull(String path) {
        if (path == null) return null;
        int idx = path.indexOf("/api/trades/");
        if (idx < 0) return null;
        String id = path.substring(idx + "/api/trades/".length());
        if (id.isBlank() || id.contains("/")) return null;
        return URLDecoder.decode(id, StandardCharsets.UTF_8);
    }

    /**
     * /api/positions/{ticker}/sector 패턴에서 ticker 만 뽑는다. 다른 경로면 null.
     */
    private static String extractSectorTickerOrNull(String path) {
        if (path == null) return null;
        int idx = path.indexOf("/api/positions/");
        if (idx < 0) return null;
        String tail = path.substring(idx + "/api/positions/".length());
        int slash = tail.indexOf('/');
        if (slash < 0) return null;
        String ticker = tail.substring(0, slash);
        String rest = tail.substring(slash);
        if (!"/sector".equals(rest)) return null;
        if (ticker.isBlank()) return null;
        return URLDecoder.decode(ticker, StandardCharsets.UTF_8);
    }

    private static APIGatewayProxyResponseEvent serverError(Exception ex) {
        // 사용자에게 stacktrace/메시지를 노출하지 않는다. 운영자는 traceId 로 CloudWatch Logs 에서 추적.
        String traceId = UUID.randomUUID().toString();
        log.error("handler failure (traceId={})", traceId, ex);
        return response(500, "{\"error\":\"internal\",\"traceId\":" + quote(traceId) + "}");
    }

    private static APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static LocalDate parseDateOrNull(Map<String, String> qs, String key) {
        if (qs == null) return null;
        String raw = qs.get(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " 날짜 형식이 잘못되었다 (yyyy-MM-dd): " + raw);
        }
    }
}
