package com.example.stockportfolio.application;

import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.PositionView;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.adapter.web.dto.TradeView;
import com.example.stockportfolio.domain.DomainException;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.IrrCalculator;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quote;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

@Service
public class PortfolioApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioApplicationService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // weight 분모가 0일 때(빈 포트폴리오)도 BigDecimal nominator/denominator 정밀도가 보존되도록 별도 scale 선언.
    private static final int WEIGHT_SCALE = 6;
    // 스냅샷 GET 기본 윈도: 최근 90일.
    private static final int DEFAULT_SNAPSHOT_DAYS = 90;

    private final PortfolioRepository repository;
    private final MarketDataPort marketDataPort;
    private final ExchangeResolver exchangeResolver;
    private final Clock clock;

    public PortfolioApplicationService(PortfolioRepository repository,
                                       MarketDataPort marketDataPort,
                                       ExchangeResolver exchangeResolver,
                                       Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.marketDataPort = Objects.requireNonNull(marketDataPort, "marketDataPort");
        this.exchangeResolver = Objects.requireNonNull(exchangeResolver, "exchangeResolver");
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

    /**
     * 거래 1건 삭제 후 남은 모든 거래를 시간순으로 replay 해 POSITION/CASH/META 를 재계산한다.
     *
     * <p>원칙:
     * <ul>
     *   <li>대상 id 가 없으면 {@link NoSuchElementException} → 컨트롤러가 404 로 매핑.
     *   <li>replay 중 도메인 불변식(잔고 부족·보유 수량 부족) 위반이면
     *       {@link TradeReplayValidationException} → 컨트롤러가 422 로 매핑. 실패 거래의 시각·종류를
     *       메시지에 포함해 사용자가 어느 거래 때문인지 식별 가능하게 한다.
     *   <li>replay 성공 시 단일 트랜잭션으로 거래 삭제 + 파생 캐시 통째 교체.
     * </ul>
     */
    public void deleteTrade(String tradeId) {
        Objects.requireNonNull(tradeId, "tradeId");
        List<Trade> all = repository.listAllTrades();
        Trade target = all.stream()
                .filter(t -> t.id().equals(tradeId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("거래를 찾을 수 없습니다: " + tradeId));

        // 시간 오름차순 sort 는 listAllTrades 가 이미 보장 — defensive 로 한 번 더.
        List<Trade> remaining = new ArrayList<>(all.size());
        for (Trade t : all) {
            if (!t.id().equals(target.id())) remaining.add(t);
        }
        remaining.sort(Comparator.comparing(Trade::executedAt).thenComparing(Trade::id));

        Portfolio currentBeforeDelete = repository.load();
        Portfolio rebuilt = new Portfolio();
        for (Trade t : remaining) {
            try {
                rebuilt.apply(t);
            } catch (DomainException ex) {
                String when = OffsetDateTime.ofInstant(t.executedAt(), KST)
                        .format(KST_TS_FMT);
                throw new TradeReplayValidationException(
                        "이 거래를 삭제하면 " + when + " KST 의 " + describe(t)
                                + " 거래에서 도메인 불변식이 깨집니다: " + ex.getMessage());
            }
        }

        repository.deleteTradeAndReplaceDerived(
                target,
                currentBeforeDelete.positions().keySet(),
                rebuilt);
    }

    private static String describe(Trade t) {
        return switch (t.type()) {
            case BUY -> "매수(" + t.ticker() + ")";
            case SELL -> "매도(" + t.ticker() + ")";
            case DEPOSIT -> "입금";
            case WITHDRAW -> "출금";
            case DIVIDEND -> "배당(" + t.ticker() + ")";
        };
    }

    public PortfolioView view() {
        Portfolio portfolio = repository.load();
        BigDecimal usdKrwRate = marketDataPort.getUsdKrwRate();
        OffsetDateTime asOf = OffsetDateTime.now(clock.withZone(KST));

        // 0단계: ticker 별 누적 배당 합 — DIVIDEND 거래 전체를 한 번 순회.
        // 매도 후 잔여 배당(보유 0) 케이스도 합계 손익에 포함되도록 보유 여부와 무관하게 집계한다.
        Map<String, BigDecimal> dividendsByTicker = new LinkedHashMap<>();
        for (Trade t : repository.listTradesByType(TradeType.DIVIDEND)) {
            String ticker = t.ticker();
            Money amount = t.cashAmount();
            if (ticker == null || amount == null) continue;
            dividendsByTicker.merge(ticker, amount.amount(), BigDecimal::add);
        }

        // 1단계: 시세 조회 (실패 격리). ticker 사전순으로 응답 안정성을 보장한다.
        Map<String, BigDecimal> lastPriceUsdByTicker = new LinkedHashMap<>();
        List<Position> sortedPositions = portfolio.positions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        for (Position p : sortedPositions) {
            try {
                Exchange exchange = exchangeResolver.resolve(p.ticker());
                Quote quote = marketDataPort.getQuote(p.ticker(), exchange);
                lastPriceUsdByTicker.put(p.ticker(), quote.price().amount());
                exchangeResolver.onQuoteSuccess(p.ticker(), clock.instant());
            } catch (RuntimeException ex) {
                exchangeResolver.onQuoteFailure(p.ticker());
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
        // - 시세 가용 종목 unrealizedPnlUsd = (현재가-평균단가)*수량 + 해당 ticker 누적배당
        // - 시세 실패 종목 unrealizedPnlUsd = null (시세에 의존), 단 누적배당은 전체 합계에 별도로 가산
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
                BigDecimal tickerDividend = dividendsByTicker.getOrDefault(p.ticker(), BigDecimal.ZERO);
                unrealizedPnlUsd = scaleMoney(marketValueUsd.subtract(costBasisUsd).add(tickerDividend));
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

        // 시세 실패 종목 + 미보유 종목의 누적배당도 전체 손익 합계에는 포함한다.
        // 시세 가용 종목은 위 루프에서 이미 unrealizedPnlUsd 에 포함됐으므로 여기서는 제외.
        for (Map.Entry<String, BigDecimal> e : dividendsByTicker.entrySet()) {
            if (lastPriceUsdByTicker.containsKey(e.getKey())) continue;
            totalUnrealizedPnlUsd = totalUnrealizedPnlUsd.add(e.getValue());
        }

        BigDecimal cashWeight = computeWeight(cashUsd, denominator);

        // 4단계: 수익률 지표 계산.
        // - 단순 누적: (현재 총자산 USD - 순 원금) / 순 원금. 순 원금 ≤ 0 이면 null.
        // - IRR: DEPOSIT/WITHDRAW/DIVIDEND 만 외부 현금흐름으로 잡고, 마지막 시점에 +현재 총자산 USD.
        BigDecimal currentTotalUsd = totalMarketValueUsd.add(cashUsd);
        BigDecimal netPrincipalUsd = portfolio.principal().amount();
        BigDecimal simpleReturn = IrrCalculator.simpleReturn(currentTotalUsd, netPrincipalUsd).orElse(null);
        BigDecimal irr = computeIrr(currentTotalUsd, asOf).orElse(null);

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
                positionViews,
                irr,
                simpleReturn);
    }

    /**
     * IRR 입력 현금흐름 시퀀스 생성 후 XIRR 호출.
     * - DEPOSIT: 음수(외부 → 포트폴리오 투입)
     * - WITHDRAW: 양수(포트폴리오 → 외부 회수)
     * - DIVIDEND: 양수(현금 입금)
     * - BUY/SELL 은 포트폴리오 내부 자산 형태 변경이므로 제외
     * - 마지막 시점(asOf)에 현재 총자산 USD 를 양수로 추가 ("지금 다 회수하면" 가치)
     */
    private Optional<BigDecimal> computeIrr(BigDecimal currentTotalUsd, OffsetDateTime asOf) {
        List<IrrCalculator.CashFlow> flows = new ArrayList<>();
        for (Trade t : repository.listTradesByType(TradeType.DEPOSIT)) {
            Money amount = t.cashAmount();
            if (amount == null) continue;
            flows.add(new IrrCalculator.CashFlow(t.executedAt(), amount.amount().negate()));
        }
        for (Trade t : repository.listTradesByType(TradeType.WITHDRAW)) {
            Money amount = t.cashAmount();
            if (amount == null) continue;
            flows.add(new IrrCalculator.CashFlow(t.executedAt(), amount.amount()));
        }
        for (Trade t : repository.listTradesByType(TradeType.DIVIDEND)) {
            Money amount = t.cashAmount();
            if (amount == null) continue;
            flows.add(new IrrCalculator.CashFlow(t.executedAt(), amount.amount()));
        }
        if (flows.isEmpty()) {
            return Optional.empty();
        }
        flows.add(new IrrCalculator.CashFlow(asOf.toInstant(), currentTotalUsd));
        return IrrCalculator.xirr(flows);
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
