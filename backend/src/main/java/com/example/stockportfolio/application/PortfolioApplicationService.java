package com.example.stockportfolio.application;

import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.PositionView;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.adapter.web.dto.TradeView;
import com.example.stockportfolio.domain.Currency;
import com.example.stockportfolio.domain.Exchange;
import com.example.stockportfolio.domain.IrrCalculator;
import com.example.stockportfolio.domain.MarketDataPort;
import com.example.stockportfolio.domain.Money;
import com.example.stockportfolio.domain.Portfolio;
import com.example.stockportfolio.domain.PortfolioRepository;
import com.example.stockportfolio.domain.Position;
import com.example.stockportfolio.domain.Quantity;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
     * 거래 1건 삭제 시 현재 Portfolio 상태에서 해당 거래의 효과를 역산해 새 상태를 계산한다.
     *
     * <p>원칙:
     * <ul>
     *   <li>대상 id 가 없으면 {@link NoSuchElementException} → 컨트롤러가 404 로 매핑.
     *   <li>전역 replay 가 아니라 <b>현재 상태 - 거래 효과</b> 로 계산하므로 backfill 패턴
     *       (과거 시각의 매수 + 최근 시각의 입금) 도 정상 처리된다. 원본 데이터(append-only 거래) 는
     *       이미 도메인 불변식을 만족한 상태로 저장돼 있으므로 그 결과인 현재 상태에서 역산하는 것이 옳다.
     *   <li>BUY/SELL 삭제는 해당 ticker 의 모든 BUY/SELL 거래(대상 제외) 를 시간순으로 ticker-local replay
     *       해 Position 을 재계산한다 (가중평균 단가가 거래 순서에 의존). DEPOSIT/WITHDRAW/DIVIDEND 삭제는
     *       현금만 단순 가감.
     *   <li>역산 결과가 도메인 불변식(음수 잔고/포지션) 을 위반하면
     *       {@link TradeReplayValidationException} → 컨트롤러가 422 로 매핑.
     *   <li>역산 성공 시 단일 트랜잭션으로 거래 삭제 + 파생 캐시 통째 교체.
     * </ul>
     */
    public void deleteTrade(String tradeId) {
        Objects.requireNonNull(tradeId, "tradeId");
        List<Trade> all = repository.listAllTrades();
        Trade target = all.stream()
                .filter(t -> t.id().equals(tradeId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("거래를 찾을 수 없습니다: " + tradeId));

        Portfolio current = repository.load();
        Portfolio rebuilt = computeStateAfterDelete(current, target, all);

        repository.deleteTradeAndReplaceDerived(
                target,
                current.positions().keySet(),
                rebuilt);
    }

    /**
     * 현재 상태 + 삭제 대상 거래 → 삭제 후 새 Portfolio. 검증 실패 시
     * {@link TradeReplayValidationException} 을 던진다. 부수 효과 없음.
     */
    private static Portfolio computeStateAfterDelete(Portfolio current, Trade target, List<Trade> allTrades) {
        // 새 positions Map 은 ticker-local replay 결과로 채우므로 BUY/SELL 이 아닌 경우엔 현재 값을 그대로 복사.
        Map<String, Position> newPositions = new HashMap<>();
        for (Map.Entry<String, Position> e : current.positions().entrySet()) {
            Position p = e.getValue();
            newPositions.put(e.getKey(), new Position(p.ticker(), p.qty(), p.avgCost(), p.realizedPnl()));
        }
        Money cash = current.cashUsd();
        Money cumulativeDeposit = current.cumulativeDeposit();
        Money cumulativeWithdraw = current.cumulativeWithdraw();

        switch (target.type()) {
            case DEPOSIT -> {
                Money amount = requireCashAmount(target);
                if (cash.isLessThan(amount)) {
                    throw new TradeReplayValidationException(
                            "현재 현금 잔고 (" + cash.amount().toPlainString() + " USD) 가 "
                                    + "삭제 대상 입금 (" + amount.amount().toPlainString() + " USD) 보다 적어 삭제할 수 없습니다");
                }
                cash = cash.subtract(amount);
                cumulativeDeposit = cumulativeDeposit.subtract(amount);
            }
            case WITHDRAW -> {
                Money amount = requireCashAmount(target);
                cash = cash.add(amount);
                cumulativeWithdraw = cumulativeWithdraw.subtract(amount);
            }
            case DIVIDEND -> {
                Money amount = requireCashAmount(target);
                if (cash.isLessThan(amount)) {
                    throw new TradeReplayValidationException(
                            "현재 현금 잔고 (" + cash.amount().toPlainString() + " USD) 가 "
                                    + "삭제 대상 배당 (" + amount.amount().toPlainString() + " USD) 보다 적어 삭제할 수 없습니다");
                }
                cash = cash.subtract(amount);
            }
            case BUY -> {
                String ticker = requireTicker(target);
                Quantity qty = requireQty(target);
                Money price = requirePrice(target);
                Money fee = target.feeOpt().orElse(Money.zero(Currency.USD));
                Money cost = price.multiply(qty.value()).add(fee);

                // BUY 삭제 → 현금이 그대로 돌아옴. 음수가 될 수 없음(현금 가감만으로는).
                cash = cash.add(cost);

                Position rebuiltPosition = recomputePosition(ticker, allTrades, target);
                if (rebuiltPosition != null && rebuiltPosition.qty().isNegative()) {
                    Position currentPosition = current.positions().get(ticker);
                    Quantity owned = currentPosition == null ? Quantity.zero() : currentPosition.qty();
                    throw new TradeReplayValidationException(
                            "이 매수를 삭제하면 종목 " + ticker + " 의 보유 수량이 음수가 됩니다 "
                                    + "(현재 " + owned + " 주, 매수 수량 " + qty + " 주)");
                }
                if (rebuiltPosition == null) {
                    newPositions.remove(ticker);
                } else {
                    newPositions.put(ticker, rebuiltPosition);
                }
            }
            case SELL -> {
                String ticker = requireTicker(target);
                Quantity qty = requireQty(target);
                Money price = requirePrice(target);
                Money fee = target.feeOpt().orElse(Money.zero(Currency.USD));
                Money proceeds = price.multiply(qty.value()).subtract(fee);

                if (cash.isLessThan(proceeds)) {
                    throw new TradeReplayValidationException(
                            "이 매도를 삭제하면 현금 잔고가 음수가 됩니다 "
                                    + "(현재 " + cash.amount().toPlainString() + " USD, "
                                    + "매도대금 " + proceeds.amount().toPlainString() + " USD)");
                }
                cash = cash.subtract(proceeds);

                Position rebuiltPosition = recomputePosition(ticker, allTrades, target);
                // 매도 삭제 → 수량 회복이므로 음수가 될 수 없으나 방어적으로 점검.
                if (rebuiltPosition != null && rebuiltPosition.qty().isNegative()) {
                    throw new TradeReplayValidationException(
                            "이 매도를 삭제하면 종목 " + ticker + " 의 보유 수량이 음수가 됩니다");
                }
                if (rebuiltPosition == null) {
                    newPositions.remove(ticker);
                } else {
                    newPositions.put(ticker, rebuiltPosition);
                }
            }
        }

        return new Portfolio(newPositions, cash, cumulativeDeposit, cumulativeWithdraw);
    }

    /**
     * 특정 ticker 의 BUY/SELL 거래(대상 제외) 만 시간순으로 누적 적용해 Position 을 재계산한다.
     * DIVIDEND 는 Position 의 수량/평균단가에 영향이 없어 무시한다(현금만 영향).
     * 가중평균 단가 산식은 Portfolio 의 BUY 적용 로직과 동일.
     * 거래가 0건이면 null 반환 → 호출자가 positions Map 에서 제거.
     *
     * <p>Portfolio 를 거치지 않는 이유: 매수 시 잔고 검증이 동반되므로 ticker-local replay 가
     * 가짜 잔고 시드로 우회해야 한다. 그보다 산식만 직접 수행하는 편이 단순.
     */
    private static Position recomputePosition(String ticker, List<Trade> allTrades, Trade target) {
        List<Trade> tickerTrades = new ArrayList<>();
        for (Trade t : allTrades) {
            if (t.id().equals(target.id())) continue;
            if (!ticker.equals(t.ticker())) continue;
            if (t.type() != TradeType.BUY && t.type() != TradeType.SELL) continue;
            tickerTrades.add(t);
        }
        if (tickerTrades.isEmpty()) {
            return null;
        }
        tickerTrades.sort(Comparator.comparing(Trade::executedAt).thenComparing(Trade::id));

        Quantity qty = Quantity.zero();
        Money avgCost = Money.zero(Currency.USD);
        Money realized = Money.zero(Currency.USD);
        for (Trade t : tickerTrades) {
            if (t.type() == TradeType.BUY) {
                Quantity newQty = qty.add(t.qty());
                BigDecimal existingValue = avgCost.amount().multiply(qty.value());
                BigDecimal incomingValue = t.price().amount().multiply(t.qty().value());
                BigDecimal newAvg = existingValue.add(incomingValue)
                        .divide(newQty.value(), Money.SCALE, Money.ROUNDING);
                qty = newQty;
                avgCost = Money.of(newAvg, Currency.USD);
            } else { // SELL
                Money fee = t.feeOpt().orElse(Money.zero(Currency.USD));
                BigDecimal pnlPerShare = t.price().amount().subtract(avgCost.amount());
                Money sellRealized = Money.of(pnlPerShare.multiply(t.qty().value()), Currency.USD).subtract(fee);
                realized = realized.add(sellRealized);
                qty = qty.subtract(t.qty());
                if (qty.isZero()) {
                    avgCost = Money.zero(Currency.USD);
                }
            }
        }
        if (qty.isZero()) {
            return null;
        }
        return new Position(ticker, qty, avgCost, realized);
    }

    private static Money requireCashAmount(Trade trade) {
        return trade.cashAmountOpt()
                .orElseThrow(() -> new IllegalStateException(
                        trade.type() + " 거래에 cashAmount 가 없습니다 (id=" + trade.id() + ")"));
    }

    private static String requireTicker(Trade trade) {
        return trade.tickerOpt()
                .orElseThrow(() -> new IllegalStateException(
                        trade.type() + " 거래에 ticker 가 없습니다 (id=" + trade.id() + ")"));
    }

    private static Quantity requireQty(Trade trade) {
        return trade.qtyOpt()
                .orElseThrow(() -> new IllegalStateException(
                        trade.type() + " 거래에 qty 가 없습니다 (id=" + trade.id() + ")"));
    }

    private static Money requirePrice(Trade trade) {
        return trade.priceOpt()
                .orElseThrow(() -> new IllegalStateException(
                        trade.type() + " 거래에 price 가 없습니다 (id=" + trade.id() + ")"));
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
        // SELL 거래의 실현 손익을 채우려면 그 시점 직전까지 ticker 별 (qty, avgCost) 가 필요하므로
        // 전체 거래를 시간순 한 번 replay 해 SELL id → realizedPnl 맵을 만든다. 1인용 + append-only 가정 하 비용 무시 가능.
        Map<String, BigDecimal> realizedByTradeId = computeRealizedPnlBySellTradeId();
        return repository.listRecentTrades(limit).stream()
                .map(t -> TradeView.from(t, realizedByTradeId.get(t.id())))
                .toList();
    }

    /**
     * 시간 오름차순으로 모든 BUY/SELL 거래를 replay 해 각 SELL 의 실현 손익(USD) 을 계산한다.
     * 평균단가는 BUY 시 가중평균으로 갱신, SELL 시 유지(전량 매도면 0 으로 리셋). 수수료는 손익을 깎는다.
     * 결과는 SELL 거래 id → 실현 손익 맵.
     */
    private Map<String, BigDecimal> computeRealizedPnlBySellTradeId() {
        Map<String, BigDecimal> result = new HashMap<>();
        Map<String, BigDecimal> qtyByTicker = new HashMap<>();
        Map<String, BigDecimal> avgCostByTicker = new HashMap<>();
        for (Trade t : repository.listAllTrades()) {
            if (t.type() != TradeType.BUY && t.type() != TradeType.SELL) continue;
            String ticker = t.ticker();
            if (ticker == null || t.qty() == null || t.price() == null) continue;
            BigDecimal qty = qtyByTicker.getOrDefault(ticker, BigDecimal.ZERO);
            BigDecimal avgCost = avgCostByTicker.getOrDefault(ticker, BigDecimal.ZERO);
            BigDecimal tradeQty = t.qty().value();
            BigDecimal tradePrice = t.price().amount();
            if (t.type() == TradeType.BUY) {
                BigDecimal newQty = qty.add(tradeQty);
                BigDecimal existingValue = avgCost.multiply(qty);
                BigDecimal incomingValue = tradePrice.multiply(tradeQty);
                BigDecimal newAvg = newQty.signum() == 0
                        ? BigDecimal.ZERO
                        : existingValue.add(incomingValue).divide(newQty, Money.SCALE, Money.ROUNDING);
                qtyByTicker.put(ticker, newQty);
                avgCostByTicker.put(ticker, newAvg);
            } else { // SELL
                BigDecimal fee = t.feeOpt().map(m -> m.amount()).orElse(BigDecimal.ZERO);
                BigDecimal pnlPerShare = tradePrice.subtract(avgCost);
                BigDecimal realized = pnlPerShare.multiply(tradeQty).subtract(fee)
                        .setScale(Money.SCALE, Money.ROUNDING);
                result.put(t.id(), realized);
                BigDecimal newQty = qty.subtract(tradeQty);
                qtyByTicker.put(ticker, newQty);
                if (newQty.signum() == 0) {
                    avgCostByTicker.put(ticker, BigDecimal.ZERO);
                }
                // 부분 매도 시 avgCost 는 유지 (가중평균 단가 정의).
            }
        }
        return result;
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
