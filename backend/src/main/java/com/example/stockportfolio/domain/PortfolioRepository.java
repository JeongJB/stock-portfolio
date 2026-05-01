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
     * 모든 거래를 시간 오름차순(executedAt ASC, id tie-break)으로 반환.
     * 거래 삭제 후 replay 등 전체 순회 용도. 1인 사용자 가정 하에 페이지네이션 미적용.
     */
    List<Trade> listAllTrades();

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

    /**
     * 거래 1건 삭제 + 모든 파생 캐시(POSITION#*, CASH#USD, META#PORTFOLIO) 를 newState 로 통째로 교체.
     * 단일 트랜잭션 내에서 처리해 중간 상태 노출을 막는다.
     * 호출자(application 계층) 가 replay 로 newState 를 미리 계산해 넘긴다.
     *
     * @param tradeToDelete 삭제 대상 거래(executedAt + id 둘 다 SK 재구성에 필요).
     * @param existingTickers 현재 저장돼 있는 모든 POSITION ticker 집합. newState 에 없으면 삭제 대상.
     * @param newState replay 후 재계산된 Portfolio.
     */
    void deleteTradeAndReplaceDerived(Trade tradeToDelete,
                                      java.util.Set<String> existingTickers,
                                      Portfolio newState);
}
