package com.example.stockportfolio.adapter.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.RecordTradeRequest;
import com.example.stockportfolio.adapter.web.dto.RecordTradeResponse;
import com.example.stockportfolio.application.PortfolioApplicationService;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Trade;
import tools.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
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

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> apiGatewayHandler(
            PortfolioApplicationService service,
            ObjectMapper mapper) {

        return event -> {
            try {
                String method = event.getHttpMethod() != null ? event.getHttpMethod() : "GET";
                String path = event.getPath() != null ? event.getPath() : "";

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

                return notFound(path);
            } catch (DomainException ex) {
                return badRequest(ex.getMessage());
            } catch (Exception ex) {
                return serverError(ex.getMessage());
            }
        };
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

    private static APIGatewayProxyResponseEvent serverError(String message) {
        return response(500, "{\"error\":\"internal\",\"message\":" + quote(message) + "}");
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
}
