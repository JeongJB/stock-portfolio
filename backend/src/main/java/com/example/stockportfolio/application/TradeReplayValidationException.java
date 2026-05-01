package com.example.stockportfolio.application;

/**
 * 거래 삭제 시 replay 결과가 도메인 불변식(음수 잔고/포지션 부족 등) 을 위반할 때 던진다.
 * 컨트롤러는 이를 422 Unprocessable Entity 로 매핑한다.
 */
public class TradeReplayValidationException extends RuntimeException {

    public TradeReplayValidationException(String message) {
        super(message);
    }
}
