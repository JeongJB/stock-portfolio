package com.example.stockportfolio.application;

import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.PositionView;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.adapter.web.dto.TradeView;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.Trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PortfolioApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioApplicationService.class);

    // 종목 마스터(P1) 도입 전이라 보유 종목의 거래소를 알 수 없다. 모두 NAS로 시세 조회.
    private static final Exchange DEFAULT_EXCHANGE = Exchange.NAS;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // weight 분모가 0일 때(빈 포트폴리오)도 BigDecimal nominator/denominator 정밀도가 보존되도록 별도 scale 선언.
    private static final int WEIGHT_SCALE = 6;
    // 스냅샷 GET 기본 윈도: 최근 90일.
    private static final int DEFAULT_SNAPSHOT_DAYS = 90;

    private final PortfolioRepository repository;
    private final MarketDataPort marketDataPort;
    private final Clock clock;

    public PortfolioApplicationService(PortfolioRepository repository,
                                       MarketDataPort marketDataPort,
                                       Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.marketDataPort = Objects.requireNonNull(marketDataPort, "marketDataPort");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Portfolio portfolio = repository.load();
        BigDecimal usdKrwRate = marketDataPort.getUsdKrwRate();
        OffsetDateTime asOf = OffsetDateTime.now(clock.withZone(KST));

        // 1단계: 시세 조회 (실패 격리). ticker 사전순으로 응답 안정성을 보장한다.
        Map<String, BigDecimal> lastPriceUsdByTicker = new LinkedHashMap<>();
        List<Position> sortedPositions = portfolio.positions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        for (Position p : sortedPositions) {
            try {
                Quote quote = marketDataPort.getQuote(p.ticker(), DEFAULT_EXCHANGE);
                lastPriceUsdByTicker.put(p.ticker(), quote.price().amount());
            } catch (RuntimeException ex) {
                log.warn("시세 조회 실패 ticker={} 무시하고 진행: {}", p.ticker(), ex.toString());
            }
        }

        // 2단계: 시세 가용 종목들의 평가액 합. 분모 = 평가액 합 + 현금.
        BigDecimal cashUsd = portfolio.cashUsd().amount();
        BigDecimal totalMarketValueUsd = BigDecimal.ZERO;
        for (Position p : sortedPositions) {
            BigDecimal price = lastPriceUsdByTicker.get(p.ticker());
            if (price == null) {
                continue;
            }
            totalMarketValueUsd = totalMarketValueUsd.add(price.multiply(p.qty().value()));
        }
        BigDecimal denominator = totalMarketValueUsd.add(cashUsd);

        // 3단계: 포지션 뷰 빌드.
        List<PositionView> positionViews = new ArrayList<>(sortedPositions.size());
        BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnlUsd = BigDecimal.ZERO;
        for (Position p : sortedPositions) {
            BigDecimal qty = p.qty().value();
            BigDecimal avgCostUsd = p.avgCost().amount();
            BigDecimal realizedPnlUsd = p.realizedPnl().amount();
            BigDecimal costBasisUsd = avgCostUsd.multiply(qty);
            totalCostBasisUsd = totalCostBasisUsd.add(costBasisUsd);

            BigDecimal lastPriceUsd = lastPriceUsdByTicker.get(p.ticker());
            BigDecimal lastPriceKrw = null;
            BigDecimal marketValueUsd = null;
            BigDecimal marketValueKrw = null;
            BigDecimal weight = null;
            BigDecimal unrealizedPnlUsd = null;
            BigDecimal unrealizedPnlKrw = null;
            if (lastPriceUsd != null) {
                lastPriceKrw = toKrw(lastPriceUsd, usdKrwRate);
                marketValueUsd = scaleMoney(lastPriceUsd.multiply(qty));
                marketValueKrw = toKrw(marketValueUsd, usdKrwRate);
                weight = computeWeight(marketValueUsd, denominator);
                unrealizedPnlUsd = scaleMoney(marketValueUsd.subtract(costBasisUsd));
                unrealizedPnlKrw = toKrw(unrealizedPnlUsd, usdKrwRate);
                totalUnrealizedPnlUsd = totalUnrealizedPnlUsd.add(unrealizedPnlUsd);
            }

            positionViews.add(new PositionView(
                    p.ticker(),
                    qty,
                    avgCostUsd,
                    toKrw(avgCostUsd, usdKrwRate),
                    realizedPnlUsd,
                    lastPriceUsd,
                    lastPriceKrw,
                    marketValueUsd,
                    marketValueKrw,
                    weight,
                    unrealizedPnlUsd,
                    unrealizedPnlKrw));
        }

        BigDecimal cashWeight = computeWeight(cashUsd, denominator);

        return new PortfolioView(
                cashUsd,
                toKrw(cashUsd, usdKrwRate),
                cashWeight,
                portfolio.principal().amount(),
                toKrw(portfolio.principal().amount(), usdKrwRate),
                portfolio.cumulativeDeposit().amount(),
                portfolio.cumulativeWithdraw().amount(),
                scaleMoney(totalMarketValueUsd),
                toKrw(totalMarketValueUsd, usdKrwRate),
                scaleMoney(totalCostBasisUsd),
                toKrw(totalCostBasisUsd, usdKrwRate),
                scaleMoney(totalUnrealizedPnlUsd),
                toKrw(totalUnrealizedPnlUsd, usdKrwRate),
                usdKrwRate,
                asOf,
                positionViews);
    }

    public List<TradeView> recentTrades(int limit) {
        return repository.listRecentTrades(limit).stream()
                .map(TradeView::from)
                .toList();
    }

    /**
     * 현재 평가 결과를 KST 기준 오늘 날짜 슬롯에 박제. 같은 날 재호출이면 덮어쓴다.
     */
    public SnapshotView takeSnapshot() {
        PortfolioView view = view();
        LocalDate date = view.asOf().toLocalDate();
        SnapshotView snapshot = new SnapshotView(
                date,
                view.asOf(),
                view.usdKrwRate(),
                view.cashUsd(),
                view.cashKrw(),
                view.principalUsd(),
                view.principalKrw(),
                view.totalMarketValueUsd(),
                view.totalMarketValueKrw(),
                view.totalCostBasisUsd(),
                view.totalCostBasisKrw(),
                view.totalUnrealizedPnlUsd(),
                view.totalUnrealizedPnlKrw(),
                view.positions());
        repository.saveSnapshot(snapshot);
        return snapshot;
    }

    /**
     * from/to 가 null 이면 KST 기준 오늘과 today-90 일로 보충해 시계열을 반환한다.
     */
    public List<SnapshotView> listSnapshots(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalDate effectiveTo = (to != null) ? to : today;
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(DEFAULT_SNAPSHOT_DAYS);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException(
                    "from(" + effectiveFrom + ")은 to(" + effectiveTo + ")보다 늦을 수 없다");
        }
        return repository.findSnapshots(effectiveFrom, effectiveTo);
    }

    private static BigDecimal toKrw(BigDecimal usd, BigDecimal rate) {
        return usd.multiply(rate).setScale(Money.SCALE, Money.ROUNDING);
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return value.setScale(Money.SCALE, Money.ROUNDING);
    }

    private static BigDecimal computeWeight(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, WEIGHT_SCALE, RoundingMode.HALF_UP);
    }
}
