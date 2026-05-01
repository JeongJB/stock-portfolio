package com.example.stockportfolio.domain;

// 한투 OpenAPI 정규장 EXCD 코드와 동일한 이름을 쓴다. 주간장(BAY/BAQ/BAA) 매핑은 KisMarketDataAdapter 내부 책임.
public enum Exchange {
    NAS,
    NYS,
    AMS
}
