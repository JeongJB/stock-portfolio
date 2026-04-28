package com.example.stockportfolio.adapter.marketdata.kis;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public class KisHttpClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final KisAccessTokenManager tokenManager;
    private final KisCredentialsProvider credentialsProvider;

    public KisHttpClient(RestClient restClient,
                         String baseUrl,
                         KisAccessTokenManager tokenManager,
                         KisCredentialsProvider credentialsProvider) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.tokenManager = tokenManager;
        this.credentialsProvider = credentialsProvider;
    }

    public JsonNode get(String path, String trId, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + path);
        queryParams.forEach(builder::queryParam);
        String token = tokenManager.getAccessToken();
        KisCredentialsProvider.KisCredentials creds = credentialsProvider.get();
        return restClient.get()
                .uri(builder.build(true).toUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("appkey", creds.appKey())
                .header("appsecret", creds.appSecret())
                .header("tr_id", trId)
                .header("custtype", "P")
                .retrieve()
                .body(JsonNode.class);
    }
}
