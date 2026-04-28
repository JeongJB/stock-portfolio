package com.example.stockportfolio.adapter.marketdata.kis;

// 자격증명 소스를 추상화하여 테스트에서 stub 으로 교체 가능하게 한다.
public interface KisCredentialsProvider {

    KisCredentials get();

    record KisCredentials(String appKey, String appSecret) {
    }
}
