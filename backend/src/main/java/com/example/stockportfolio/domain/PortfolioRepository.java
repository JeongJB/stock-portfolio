package com.example.stockportfolio.domain;

import com.example.stockportfolio.adapter.web.dto.SnapshotView;

import java.time.LocalDate;
import java.util.List;

/**
 * 포트폴리오 영속 포트. 어댑터 구현(DynamoDB 등)은 인프라 계층에 둔다.
 */
public interface PortfolioRepository {

    /** 비어있으면 빈 Portfolio 반환 (NPE 던지지 말 것). */
    Portfolio load();

    /**
     * Trade를 append하고 갱신된 Portfolio 상태(cash, positions, principal)를 원자적으로 저장.
     * DynamoDB TransactWriteItems 등 트랜잭션 메커니즘을 사용해야 한다.
     */
    void recordTrade(Trade trade, Portfolio updatedState);

    /** SK prefix `TRADE#` 역순 Query. 최신 순 limit개 반환. */
    List<Trade> listRecentTrades(int limit);

    /**
     * 특정 type 거래 전체를 시간순(오름차순)으로 반환.
     * 종목별 누적 배당 합산 등 도메인 집계 용도. 1인 사용자 가정 하에 페이지네이션 미적용.
     */
    List<Trade> listTradesByType(TradeType type);

    /**
     * 특정 종목의 BUY/SELL 거래만 GSI1 (`gsi1pk = TICKER#&lt;sym&gt;`) 로 최신순 limit 개 반환.
     * DEPOSIT/WITHDRAW 는 GSI1 키가 없어 자동으로 제외된다. 보유 종목별 거래 이력 화면 등에서 사용.
     */
    List<Trade> listTradesByTicker(String ticker, int limit);

    /**
     * 스냅샷을 저장한다. 같은 date(KST yyyy-MM-dd)면 덮어쓴다.
     * SK = SNAPSHOT#&lt;isoDate&gt;.
     */
    void saveSnapshot(SnapshotView snapshot);

    /**
     * SK 범위 쿼리로 from~to(둘 다 inclusive) 사이 스냅샷을 날짜 오름차순으로 반환.
     */
    List<SnapshotView> findSnapshots(LocalDate from, LocalDate to);
}
