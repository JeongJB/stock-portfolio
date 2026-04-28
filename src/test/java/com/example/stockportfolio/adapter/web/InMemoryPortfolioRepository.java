package com.example.stockportfolio.adapter.web;

import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 컨트롤러 IT용 인메모리 fake. recordTrade는 단순 append + 갱신 상태 전체 교체.
 * load는 매번 새 Portfolio 객체를 빌드해 컨트롤러가 mutating apply를 안전하게 쓰도록 한다.
 */
public class InMemoryPortfolioRepository implements PortfolioRepository {

    private final List<Trade> trades = new ArrayList<>();
    private final Set<String> tradeIds = new HashSet<>();
    private Map<String, Position> positions = new HashMap<>();
    private Money cashUsd = Money.zero(Currency.USD);
    private Money cumulativeDeposit = Money.zero(Currency.USD);
    private Money cumulativeWithdraw = Money.zero(Currency.USD);

    public synchronized void reset() {
        trades.clear();
        tradeIds.clear();
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
}
