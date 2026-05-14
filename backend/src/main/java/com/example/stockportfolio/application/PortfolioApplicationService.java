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
import com.example.stockportfolio.domain.TickerMeta;
import com.example.stockportfolio.domain.TickerMetaRepository;
import com.example.stockportfolio.domain.Trade;
import com.example.stockportfolio.domain.TradeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PortfolioApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioApplicationService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // weight 분모가 0일 때(빈 포트폴리오)도 BigDecimal nominator/denominator 정밀도가 보존되도록 별도 scale 선언.
    private static final int WEIGHT_SCALE = 6;
    // 52주 위치 비율은 0~1 범위, 소수점 4자리 (1bp 단위) 로 충분한 시각화 정밀도.
    private static final int WEEK_RANGE_RATIO_SCALE = 4;
    // 스냅샷 GET 기본 윈도: 최근 90일.
    private static final int DEFAULT_SNAPSHOT_DAYS = 90;
    // view() 의 종목별 시세 조회 병렬화용 풀. 1인용 종목 수와 KIS 동시 호출 한도를 고려해 보수적으로 8.
    // Lambda 단일 인스턴스 재사용 가정으로 1회 생성 후 재사용 (shutdown 훅 미설치 — 컨테이너 종료에 맡김).
    private static final int QUOTE_FETCH_POOL_SIZE = 8;

    private final PortfolioRepository repository;
    private final MarketDataPort marketDataPort;
    private final ExchangeResolver exchangeResolver;
    private final TickerMetaRepository tickerMetaRepository;
    private final Clock clock;
    private final ExecutorService quoteFetchExecutor;

    public PortfolioApplicationService(PortfolioRepository repository,
                                       MarketDataPort marketDataPort,
                                       ExchangeResolver exchangeResolver,
                                       TickerMetaRepository tickerMetaRepository,
                                       Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.marketDataPort = Objects.requireNonNull(marketDataPort, "marketDataPort");
        this.exchangeResolver = Objects.requireNonNull(exchangeResolver, "exchangeResolver");
        this.tickerMetaRepository = Objects.requireNonNull(tickerMetaRepository, "tickerMetaRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.quoteFetchExecutor = Executors.newFixedThreadPool(QUOTE_FETCH_POOL_SIZE, r -> {
            Thread t = new Thread(r, "quote-fetch");
            t.setDaemon(true);
            return t;
        });
    }

    public Trade recordTrade(RecordTradeCommand command) {
        // load() 호출마다 새 Portfolio가 빌드되므로 mutating apply가 안전하다
        Portfolio current = repository.load();
        Trade trade = command.toTrade();
        current.apply(trade);
        repository.recordTrade(trade, current);
        // 거래 트랜잭션 성공 후 sector 박제 — 별도 PutItem, best-effort.
        // 거래 PUT 의 TransactWriteItems 와 분리되어 있어 sector 갱신 실패가 거래 자체를 깨뜨리지 않는다.
        if (command.type() == TradeType.BUY && command.sector() != null && trade.ticker() != null) {
            persistSectorBestEffort(trade.ticker(), command.sector());
        }
        return trade;
    }

    /**
     * 사용자가 명시적으로 호출하는 sector 변경. BUY 와 달리 best-effort 가 아니라
     * 실패는 그대로 전파(컨트롤러가 5xx 매핑) 한다.
     *
     * <p>입력 정규화는 호출자(컨트롤러) 책임. 이 메서드는 이미 정규화된 sector 값
     * (null 또는 trim 된 길이 ≤ 30 문자열) 만 받는다.
     *
     * <p>META 가 없으면 NAS 거래소로 임시 박제 — BUY 의 {@link #persistSectorBestEffort}
     * 와 동일 패턴. 다음 view() 의 자기치유 흐름이 정확한 거래소로 정정한다.
     *
     * @return 갱신된 META 의 sector (null 가능 — 분류 제거 시).
     */
    public String updateSector(String ticker, String normalizedSector) {
        Objects.requireNonNull(ticker, "ticker");
        if (ticker.isBlank()) {
            throw new IllegalArgumentException("ticker 는 비어 있을 수 없다");
        }
        Optional<TickerMeta> existing = tickerMetaRepository.find(ticker);
        TickerMeta updated = existing
                .map(meta -> meta.withSector(normalizedSector))
                .orElseGet(() -> new TickerMeta(ticker, Exchange.NAS, clock.instant(), 0, normalizedSector));
        tickerMetaRepository.save(updated);
        return updated.sector();
    }

    /**
     * BUY 거래 직후 ticker 의 META.sector 를 갱신한다.
     * <ul>
     *   <li>META 가 이미 있으면 sector 만 교체 후 save.</li>
     *   <li>META 가 없으면 NAS 거래소로 임시 박제(sector 포함). 다음 view() 의 자기치유 흐름이
     *       카운터 누적 후 재탐색으로 정확한 거래소로 정정한다 ({@link ExchangeResolver#onQuoteSuccess}
     *       의 보수적 NAS 박제 패턴과 동일).</li>
     * </ul>
     * 어떤 예외도 호출자로 전파하지 않는다 (best-effort).
     */
    private void persistSectorBestEffort(String ticker, String sector) {
        try {
            Optional<TickerMeta> existing = tickerMetaRepository.find(ticker);
            TickerMeta updated = existing
                    .map(meta -> meta.withSector(sector))
                    .orElseGet(() -> new TickerMeta(ticker, Exchange.NAS, clock.instant(), 0, sector));
            tickerMetaRepository.save(updated);
        } catch (RuntimeException ex) {
            log.warn("sector 갱신 실패 ticker={} sector={} 무시하고 진행: {}", ticker, sector, ex.toString());
        }
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

        // 거래 전체를 한 번만 fetch — DIVIDEND 누적·IRR cashflow 모두 인메모리에서 분기한다.
        // (이전: listTradesByType(DIVIDEND) + IRR 안에서 DEPOSIT/WITHDRAW/DIVIDEND 3회 = 풀스캔 4회 — DIVIDEND 중복.)
        List<Trade> allTrades = repository.listAllTrades();

        // 0단계: ticker 별 누적 배당 합. 매도 후 잔여 배당(보유 0) 도 합계 손익에 포함되도록 보유 여부와 무관하게 집계한다.
        Map<String, BigDecimal> dividendsByTicker = new LinkedHashMap<>();
        for (Trade t : allTrades) {
            if (t.type() != TradeType.DIVIDEND) continue;
            String ticker = t.ticker();
            Money amount = t.cashAmount();
            if (ticker == null || amount == null) continue;
            dividendsByTicker.merge(ticker, amount.amount(), BigDecimal::add);
        }

        // 1단계: 시세 조회 (실패 격리). 종목별 KIS 호출을 병렬화 — 콜드 스타트 페널티가 종목 수만큼 누적되는 것을 막는다.
        // 응답 안정성(positionViews 순서) 은 다음 빌드 루프의 sortedPositions 순회로 보장된다.
        List<Position> sortedPositions = portfolio.positions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        // Quote 자체를 보관해 가격 외 보조 필드(등락률·52주 고저) 까지 PositionView 로 그대로 전파한다.
        Map<String, Quote> quoteByTicker = new ConcurrentHashMap<>();
        // META.sector 도 동일 fetch 결과에서 추출해 보관 — 추가 GetItem 발생 안 함.
        // 시세가 실패해도 META 가 잡혔다면 sector 는 노출 가능 (시세 실패 종목도 sector 별 그룹화에 활용).
        Map<String, String> sectorByTicker = new ConcurrentHashMap<>();
        CompletableFuture<?>[] quoteFutures = sortedPositions.stream()
                .map(p -> CompletableFuture.runAsync(() -> {
                    try {
                        TickerMeta meta = exchangeResolver.resolveWithMeta(p.ticker());
                        if (meta.sector() != null) {
                            sectorByTicker.put(p.ticker(), meta.sector());
                        }
                        Quote quote = marketDataPort.getQuote(p.ticker(), meta.exchange());
                        quoteByTicker.put(p.ticker(), quote);
                        exchangeResolver.onQuoteSuccess(p.ticker(), clock.instant());
                    } catch (RuntimeException ex) {
                        exchangeResolver.onQuoteFailure(p.ticker());
                        log.warn("시세 조회 실패 ticker={} 무시하고 진행: {}", p.ticker(), ex.toString());
                    }
                }, quoteFetchExecutor))
                .toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(quoteFutures).join();

        // 시세 기준 시각 = 종목별 Quote.asOf 의 최솟값. 시세 0개일 때 null.
        // 가장 오래된 슬롯을 노출해야 사용자가 "가장 stale 한 데이터" 기준을 본다.
        Instant quoteAsOf = quoteByTicker.values().stream()
                .map(Quote::asOf)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        // 2단계: 시세 가용 종목들의 평가액 합. 분모 = 평가액 합 + 현금.
        BigDecimal cashUsd = portfolio.cashUsd().amount();
        BigDecimal totalMarketValueUsd = BigDecimal.ZERO;
        for (Position p : sortedPositions) {
            Quote q = quoteByTicker.get(p.ticker());
            if (q == null) {
                continue;
            }
            totalMarketValueUsd = totalMarketValueUsd.add(q.price().amount().multiply(p.qty().value()));
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

            Quote quote = quoteByTicker.get(p.ticker());
            BigDecimal lastPriceUsd = quote == null ? null : quote.price().amount();
            BigDecimal lastPriceKrw = null;
            BigDecimal marketValueUsd = null;
            BigDecimal marketValueKrw = null;
            BigDecimal weight = null;
            BigDecimal unrealizedPnlUsd = null;
            BigDecimal unrealizedPnlKrw = null;
            BigDecimal dailyChangePct = null;
            BigDecimal weekHigh52Usd = null;
            BigDecimal weekLow52Usd = null;
            BigDecimal weekRangeRatio = null;
            if (quote != null) {
                lastPriceKrw = toKrw(lastPriceUsd, usdKrwRate);
                marketValueUsd = scaleMoney(lastPriceUsd.multiply(qty));
                marketValueKrw = toKrw(marketValueUsd, usdKrwRate);
                weight = computeWeight(marketValueUsd, denominator);
                BigDecimal tickerDividend = dividendsByTicker.getOrDefault(p.ticker(), BigDecimal.ZERO);
                unrealizedPnlUsd = scaleMoney(marketValueUsd.subtract(costBasisUsd).add(tickerDividend));
                unrealizedPnlKrw = toKrw(unrealizedPnlUsd, usdKrwRate);
                totalUnrealizedPnlUsd = totalUnrealizedPnlUsd.add(unrealizedPnlUsd);

                dailyChangePct = quote.dailyChangePct();
                weekHigh52Usd = quote.weekHigh52();
                weekLow52Usd = quote.weekLow52();
                weekRangeRatio = computeWeekRangeRatio(lastPriceUsd, weekHigh52Usd, weekLow52Usd);
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
                    unrealizedPnlKrw,
                    dailyChangePct,
                    weekHigh52Usd,
                    weekLow52Usd,
                    weekRangeRatio,
                    sectorByTicker.get(p.ticker())));
        }

        // 시세 실패 종목 + 미보유 종목의 누적배당도 전체 손익 합계에는 포함한다.
        // 시세 가용 종목은 위 루프에서 이미 unrealizedPnlUsd 에 포함됐으므로 여기서는 제외.
        for (Map.Entry<String, BigDecimal> e : dividendsByTicker.entrySet()) {
            if (quoteByTicker.containsKey(e.getKey())) continue;
            totalUnrealizedPnlUsd = totalUnrealizedPnlUsd.add(e.getValue());
        }

        BigDecimal cashWeight = computeWeight(cashUsd, denominator);

        // 4단계: 수익률 지표 계산.
        // - 단순 누적: (현재 총자산 USD - 순 원금) / 순 원금. 순 원금 ≤ 0 이면 null.
        // - IRR: DEPOSIT/WITHDRAW/DIVIDEND 만 외부 현금흐름으로 잡고, 마지막 시점에 +현재 총자산 USD.
        BigDecimal currentTotalUsd = totalMarketValueUsd.add(cashUsd);
        BigDecimal netPrincipalUsd = portfolio.principal().amount();
        BigDecimal simpleReturn = IrrCalculator.simpleReturn(currentTotalUsd, netPrincipalUsd).orElse(null);
        BigDecimal irr = computeIrr(allTrades, currentTotalUsd, asOf).orElse(null);

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
                scaleMoney(currentTotalUsd),
                toKrw(currentTotalUsd, usdKrwRate),
                usdKrwRate,
                asOf,
                positionViews,
                irr,
                simpleReturn,
                quoteAsOf,
                quoteByTicker.size(),
                sortedPositions.size());
    }

    /**
     * IRR 입력 현금흐름 시퀀스 생성 후 XIRR 호출. 호출자가 view() 진입 시 fetch 한 거래 리스트를 그대로 넘긴다.
     * - DEPOSIT: 음수(외부 → 포트폴리오 투입)
     * - WITHDRAW: 양수(포트폴리오 → 외부 회수)
     * - DIVIDEND: 양수(현금 입금)
     * - BUY/SELL 은 포트폴리오 내부 자산 형태 변경이므로 제외
     * - 마지막 시점(asOf)에 현재 총자산 USD 를 양수로 추가 ("지금 다 회수하면" 가치)
     */
    private Optional<BigDecimal> computeIrr(List<Trade> allTrades, BigDecimal currentTotalUsd, OffsetDateTime asOf) {
        List<IrrCalculator.CashFlow> flows = new ArrayList<>();
        for (Trade t : allTrades) {
            Money amount = t.cashAmount();
            if (amount == null) continue;
            switch (t.type()) {
                case DEPOSIT -> flows.add(new IrrCalculator.CashFlow(t.executedAt(), amount.amount().negate()));
                case WITHDRAW, DIVIDEND -> flows.add(new IrrCalculator.CashFlow(t.executedAt(), amount.amount()));
                default -> {}
            }
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
                view.totalAssetsUsd(),
                view.totalAssetsKrw());
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

    /**
     * 52주 위치 비율 = (last - low) / (high - low). 0~1 로 clamp (현재가가 범위 밖이어도 마커가 트랙 안에 머물도록).
     * high == low 이거나 둘 중 하나가 null 이면 비율 계산이 무의미 → null.
     */
    private static BigDecimal computeWeekRangeRatio(BigDecimal last, BigDecimal high, BigDecimal low) {
        if (last == null || high == null || low == null) {
            return null;
        }
        BigDecimal range = high.subtract(low);
        if (range.signum() <= 0) {
            return null;
        }
        BigDecimal ratio = last.subtract(low).divide(range, WEEK_RANGE_RATIO_SCALE, RoundingMode.HALF_UP);
        if (ratio.signum() < 0) {
            return BigDecimal.ZERO.setScale(WEEK_RANGE_RATIO_SCALE);
        }
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(WEEK_RANGE_RATIO_SCALE);
        }
        return ratio;
    }
}
