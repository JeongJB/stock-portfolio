package com.example.stockportfolio.application;

import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.TradeView;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Trade;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class PortfolioApplicationService {

    private final PortfolioRepository repository;

    public PortfolioApplicationService(PortfolioRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Trade recordTrade(RecordTradeCommand command) {
        // load() 호출마다 새 Portfolio가 빌드되므로 mutating apply가 안전하다
        Portfolio current = repository.load();
        Trade trade = command.toTrade();
        current.apply(trade);
        repository.recordTrade(trade, current);
        return trade;
    }

    public PortfolioView view() {
        return PortfolioView.from(repository.load());
    }

    public List<TradeView> recentTrades(int limit) {
        return repository.listRecentTrades(limit).stream()
                .map(TradeView::from)
                .toList();
    }
}
