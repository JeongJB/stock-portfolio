package com.example.stockportfolio.adapter.web;

import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 컨트롤러 IT용 인메모리 fake. recordTrade는 단순 append + 갱신 상태 전체 교체.
 * load는 매번 새 Portfolio 객체를 빌드해 컨트롤러가 mutating apply를 안전하게 쓰도록 한다.
 */
public class InMemoryPortfolioRepository implements PortfolioRepository {

    private final List<Trade> trades = new ArrayList<>();
    private final Set<String> tradeIds = new HashSet<>();
    private final TreeMap<LocalDate, SnapshotView> snapshots = new TreeMap<>();
    private Map<String, Position> positions = new HashMap<>();
    private Money cashUsd = Money.zero(Currency.USD);
    private Money cumulativeDeposit = Money.zero(Currency.USD);
    private Money cumulativeWithdraw = Money.zero(Currency.USD);

    public synchronized void reset() {
        trades.clear();
        tradeIds.clear();
        snapshots.clear();
        positions.clear();
        cashUsd = Money.zero(Currency.USD);
        cumulativeDeposit = Money.zero(Currency.USD);
        cumulativeWithdraw = Money.zero(Currency.USD);
    }

    @Override
    public synchronized Portfolio load() {
        Map<String, Position> snapshot = new HashMap<>();
        // 도메인은 Position이 mutable이므로 새 인스턴스로 깊은 복사
        positions.forEach((k, v) ->
                snapshot.put(k, new Position(v.ticker(), v.qty(), v.avgCost(), v.realizedPnl())));
        return new Portfolio(snapshot, cashUsd, cumulativeDeposit, cumulativeWithdraw);
    }

    @Override
    public synchronized void recordTrade(Trade trade, Portfolio updatedState) {
        if (!tradeIds.add(trade.id())) {
            throw new DomainException("거래 저장 실패 (중복): " + trade.id());
        }
        trades.add(trade);
        // 깊은 복사로 외부 mutation으로부터 격리
        Map<String, Position> snapshot = new HashMap<>();
        updatedState.positions().forEach((k, v) ->
                snapshot.put(k, new Position(v.ticker(), v.qty(), v.avgCost(), v.realizedPnl())));
        this.positions = snapshot;
        this.cashUsd = updatedState.cashUsd();
        this.cumulativeDeposit = updatedState.cumulativeDeposit();
        this.cumulativeWithdraw = updatedState.cumulativeWithdraw();
    }

    @Override
    public synchronized List<Trade> listRecentTrades(int limit) {
        List<Trade> sorted = new ArrayList<>(trades);
        // 최신순 정렬 (executedAt 내림차순, tie-break: id)
        sorted.sort(Comparator.comparing(Trade::executedAt).reversed()
                .thenComparing(Trade::id));
        if (sorted.size() <= limit) {
            return Collections.unmodifiableList(sorted);
        }
        return Collections.unmodifiableList(sorted.subList(0, limit));
    }

    @Override
    public synchronized List<Trade> listAllTrades() {
        List<Trade> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(Trade::executedAt).thenComparing(Trade::id));
        return Collections.unmodifiableList(sorted);
    }

    @Override
    public synchronized List<Trade> listTradesByType(TradeType type) {
        List<Trade> filtered = new ArrayList<>();
        for (Trade t : trades) {
            if (t.type() == type) filtered.add(t);
        }
        // 시간 오름차순 — DynamoPortfolioRepository.listTradesByType 과 일관.
        filtered.sort(Comparator.comparing(Trade::executedAt).thenComparing(Trade::id));
        return Collections.unmodifiableList(filtered);
    }

    @Override
    public synchronized List<Trade> listTradesByTicker(String ticker, int limit) {
        List<Trade> filtered = new ArrayList<>();
        for (Trade t : trades) {
            if (ticker.equals(t.ticker())
                    && (t.type() == com.example.stockportfolio.domain.TradeType.BUY
                            || t.type() == com.example.stockportfolio.domain.TradeType.SELL)) {
                filtered.add(t);
            }
        }
        filtered.sort(Comparator.comparing(Trade::executedAt).reversed()
                .thenComparing(Trade::id));
        if (filtered.size() <= limit) {
            return Collections.unmodifiableList(filtered);
        }
        return Collections.unmodifiableList(filtered.subList(0, limit));
    }

    @Override
    public synchronized void saveSnapshot(SnapshotView snapshot) {
        snapshots.put(snapshot.date(), snapshot);
    }

    @Override
    public synchronized List<SnapshotView> findSnapshots(LocalDate from, LocalDate to) {
        // TreeMap.subMap inclusive both — DynamoDB BETWEEN과 동일 의미
        return List.copyOf(snapshots.subMap(from, true, to, true).values());
    }

    @Override
    public synchronized void deleteTradeAndReplaceDerived(Trade tradeToDelete,
                                                          Set<String> existingTickers,
                                                          Portfolio newState) {
        if (!tradeIds.remove(tradeToDelete.id())) {
            throw new DomainException("거래 삭제 실패 (미존재): " + tradeToDelete.id());
        }
        trades.removeIf(t -> t.id().equals(tradeToDelete.id()));
        // 깊은 복사로 외부 mutation 격리 — recordTrade 와 동일 패턴.
        Map<String, Position> snapshot = new HashMap<>();
        newState.positions().forEach((k, v) ->
                snapshot.put(k, new Position(v.ticker(), v.qty(), v.avgCost(), v.realizedPnl())));
        this.positions = snapshot;
        this.cashUsd = newState.cashUsd();
        this.cumulativeDeposit = newState.cumulativeDeposit();
        this.cumulativeWithdraw = newState.cumulativeWithdraw();
    }
}
