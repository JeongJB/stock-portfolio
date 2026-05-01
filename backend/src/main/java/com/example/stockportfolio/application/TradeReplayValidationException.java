package com.example.stockportfolio.application;

/**
 * 거래 삭제 시 무결성 검증(현재 상태에서 거래 효과 역산 후 음수 잔고/포지션 발생) 을 위반할 때 던진다.
 * 컨트롤러는 이를 422 Unprocessable Entity 로 매핑한다.
 *
 * <p>이름은 과거 전역 replay 검증 방식의 잔재이지만, HTTP 상태 매핑(422)을 유지하기 위해 그대로 둔다.
 */
public class TradeReplayValidationException extends RuntimeException {

    public TradeReplayValidationException(String message) {
        super(message);
    }
}
