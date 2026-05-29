package com.example.stockportfolio.adapter.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.stockportfolio.application.PortfolioApplicationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LambdaConfig.apiGatewayHandler 의 라우팅 로직 유닛 테스트.
 *
 * Lambda 환경에서는 Spring MVC DispatcherServlet 이 아닌 이 함수가 직접 HTTP 라우팅을 담당한다.
 * PortfolioControllerIT 는 MockMvc 로 Spring MVC 를 테스트하므로 Lambda 라우팅 회귀를
 * 잡지 못한다 — 여기서 별도로 검증한다.
 *
 * <p>테스트 환경(AWS_LAMBDA_FUNCTION_NAME 미설정)에서는 verifyOrigin 이 자동으로 통과된다.
 */
class LambdaConfigTest {

    private PortfolioApplicationService service;
    private Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handler;

    @BeforeEach
    void setUp() {
        service = mock(PortfolioApplicationService.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        handler = new LambdaConfig().apiGatewayHandler(service, mapper);
    }

    // ─── PATCH /api/positions/{ticker}/sector ──────────────────────────────

    @Test
    void PATCH_sector_정상_요청은_200과_갱신된_값을_반환한다() {
        when(service.updateSector("GEV", "반도체")).thenReturn("반도체");

        var res = patch("/api/positions/GEV/sector", "{\"sector\":\"반도체\"}");

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody())
                .contains("\"ticker\":\"GEV\"")
                .contains("\"sector\":\"반도체\"");
    }

    @Test
    void PATCH_sector_소문자_ticker는_대문자로_정규화된다() {
        when(service.updateSector("AAPL", "Big Tech")).thenReturn("Big Tech");

        var res = patch("/api/positions/aapl/sector", "{\"sector\":\"Big Tech\"}");

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"ticker\":\"AAPL\"");
    }

    @Test
    void PATCH_sector_null은_분류_제거를_의미한다() {
        when(service.updateSector("GEV", null)).thenReturn(null);

        var res = patch("/api/positions/GEV/sector", "{\"sector\":null}");

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"sector\":null");
    }

    @Test
    void PATCH_sector_빈_문자열은_분류_제거로_처리된다() {
        when(service.updateSector("GEV", null)).thenReturn(null);

        var res = patch("/api/positions/GEV/sector", "{\"sector\":\"\"}");

        assertThat(res.getStatusCode()).isEqualTo(200);
    }

    @Test
    void PATCH_sector_공백만_있으면_분류_제거로_처리된다() {
        when(service.updateSector("GEV", null)).thenReturn(null);

        var res = patch("/api/positions/GEV/sector", "{\"sector\":\"   \"}");

        assertThat(res.getStatusCode()).isEqualTo(200);
    }

    @Test
    void PATCH_sector_30자_초과는_422를_반환한다() {
        var res = patch("/api/positions/GEV/sector",
                "{\"sector\":\"" + "A".repeat(31) + "\"}");

        assertThat(res.getStatusCode()).isEqualTo(422);
        assertThat(res.getBody()).contains("validation_failed");
    }

    @Test
    void PATCH_sector_경로에_stage_prefix가_붙어도_정상_라우팅된다() {
        // API Gateway REST API 의 event.path 에는 stage prefix 가 없지만
        // 혹시 포함되더라도 indexOf 매칭으로 정상 처리됨을 보장.
        when(service.updateSector("GEV", "IT")).thenReturn("IT");

        var res = patch("/prod/api/positions/GEV/sector", "{\"sector\":\"IT\"}");

        assertThat(res.getStatusCode()).isEqualTo(200);
    }

    // ─── 404 fallback 검증 — 매칭되지 않는 경로 ──────────────────────────

    @Test
    void PATCH_sector_suffix_없는_경로는_404를_반환한다() {
        // /api/positions/GEV (sector 없음)
        var res = patch("/api/positions/GEV", "{}");

        assertThat(res.getStatusCode()).isEqualTo(404);
        assertThat(res.getBody()).contains("\"error\":\"not_found\"");
    }

    @Test
    void PATCH_미등록_경로는_404를_반환한다() {
        var res = patch("/api/unknown/path", "{}");

        assertThat(res.getStatusCode()).isEqualTo(404);
        assertThat(res.getBody()).contains("/api/unknown/path");
    }

    @Test
    void PATCH_sector_대신_다른_suffix면_404를_반환한다() {
        // /api/positions/GEV/exchange → sector 가 아니므로 매칭 안 됨
        var res = patch("/api/positions/GEV/exchange", "{}");

        assertThat(res.getStatusCode()).isEqualTo(404);
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────

    private APIGatewayProxyResponseEvent patch(String path, String body) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("PATCH");
        event.setPath(path);
        event.setBody(body);
        return handler.apply(event);
    }
}
