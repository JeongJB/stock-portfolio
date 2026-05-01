package com.example.stockportfolio.adapter.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.RecordTradeRequest;
import com.example.stockportfolio.adapter.web.dto.RecordTradeResponse;
import com.example.stockportfolio.adapter.web.dto.SnapshotListResponse;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.application.PortfolioApplicationService;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Trade;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
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

    // 운영 Lambda 는 CORS_ALLOWED_ORIGIN 환경변수가 항상 박혀 있다 (SAM 에서 주입).
    // 로컬 개발 / 단위 테스트 환경 호환을 위해 미설정 시에만 와일드카드로 폴백.
    private static final String CORS_ALLOWED_ORIGIN = resolveAllowedOrigin();

    private static String resolveAllowedOrigin() {
        String env = System.getenv("CORS_ALLOWED_ORIGIN");
        return (env == null || env.isBlank()) ? "*" : env;
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> apiGatewayHandler(
            PortfolioApplicationService service,
            ObjectMapper mapper) {

        return event -> {
            try {
                String method = event.getHttpMethod() != null ? event.getHttpMethod() : "GET";
                String path = event.getPath() != null ? event.getPath() : "";

                // CORS preflight: SAM 의 Cors Mock 이 Path: /api/{proxy+} + Method: ANY 와
                // 충돌해 ANY 가 OPTIONS 를 잡아챌 수 있다. Lambda 가 직접 응답해 안전하게.
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    return preflight();
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

                return notFound(path);
            } catch (DomainException ex) {
                return badRequest(ex.getMessage());
            } catch (Exception ex) {
                return serverError(ex);
            }
        };
    }

    private static APIGatewayProxyResponseEvent preflight() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(204)
                .withHeaders(Map.of(
                        "Access-Control-Allow-Origin", CORS_ALLOWED_ORIGIN,
                        "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,OPTIONS",
                        "Access-Control-Allow-Headers", "Content-Type,x-api-key",
                        "Access-Control-Max-Age", "3600"));
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

    private static APIGatewayProxyResponseEvent badRequest(String message) {
        return response(400, "{\"error\":\"domain_error\",\"message\":" + quote(message) + "}");
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
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        // CloudFront 도메인에서 호출되는 SPA 라 actual response 에도 CORS 헤더 필수.
                        // API Gateway Cors 설정은 OPTIONS preflight 만 처리하고 GET/POST 응답엔 영향 없다.
                        "Access-Control-Allow-Origin", CORS_ALLOWED_ORIGIN))
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
