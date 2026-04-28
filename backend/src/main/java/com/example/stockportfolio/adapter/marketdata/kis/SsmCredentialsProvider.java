package com.example.stockportfolio.adapter.marketdata.kis;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.concurrent.atomic.AtomicReference;

public class SsmCredentialsProvider implements KisCredentialsProvider {

    private final SsmClient ssmClient;
    private final String appKeyParameter;
    private final String appSecretParameter;
    // Lambda 인스턴스 수명 동안 SSM 호출을 1회로 줄이기 위한 캐시.
    private final AtomicReference<KisCredentials> cache = new AtomicReference<>();

    public SsmCredentialsProvider(SsmClient ssmClient, String appKeyParameter, String appSecretParameter) {
        this.ssmClient = ssmClient;
        this.appKeyParameter = appKeyParameter;
        this.appSecretParameter = appSecretParameter;
    }

    @Override
    public KisCredentials get() {
        KisCredentials current = cache.get();
        if (current != null) {
            return current;
        }
        String appKey = fetch(appKeyParameter);
        String appSecret = fetch(appSecretParameter);
        KisCredentials loaded = new KisCredentials(appKey, appSecret);
        cache.compareAndSet(null, loaded);
        return cache.get();
    }

    private String fetch(String name) {
        return ssmClient.getParameter(GetParameterRequest.builder()
                        .name(name)
                        .withDecryption(true)
                        .build())
                .parameter()
                .value();
    }
}
