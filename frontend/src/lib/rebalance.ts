// 비중 리밸런싱 계산기 (pure function).
// 분모는 portfolio.totalMarketValueUsd (현금 제외 주식 가치 합). 입력은 모두 number 로 받는다 —
// 호출부에서 BigDecimal string → Number 변환 책임.

import type { PortfolioView, PositionView } from '../api/types'

export type RebalanceAction = 'BUY' | 'SELL' | 'NONE'

export interface RebalanceInput {
  ticker: string
  qty: number // 현재 보유 수량
  lastPriceUsd: number
  marketValueUsd: number
  cashUsd: number
  totalMarketValueUsd: number
  targetWeightPct: number // 0 < x ≤ 100
}

export interface RebalanceResult {
  action: RebalanceAction
  qty: number // 매수/매도 주 수 (양수). NONE 이면 0.
  costUsd: number // 매수/매도 거래액 (양수). NONE 이면 0.
  realizedWeightPct: number // 실현 비중 (%)
  errorPct: number // 목표 - 실현 (%, 부호 있음 — 양수면 목표 미달)
  postCashUsd: number // 거래 후 현금 잔고 USD
  cashShortage: boolean // 매수 시 현금 부족 여부
  maxAffordableQty: number // 매수 시 cash 로 가능한 최대 주 수 (현금 부족 안내용)
  cappedBySellQty: boolean // 매도 시 보유 수량 초과로 cap 발생 여부
  currentWeightPct: number // 현재 비중 (%) — 표시 편의
}

/**
 * 목표 비중에 맞춰 매수/매도 주 수를 계산.
 * truncate (0방향) 사용 — 매수든 매도든 안전한 내림.
 * 필요주수 0 이면 NONE.
 */
export function computeRebalance(input: RebalanceInput): RebalanceResult {
  const {
    qty: currentQty,
    lastPriceUsd,
    marketValueUsd,
    cashUsd,
    totalMarketValueUsd,
    targetWeightPct,
  } = input

  const currentWeightPct =
    totalMarketValueUsd > 0 ? (marketValueUsd / totalMarketValueUsd) * 100 : 0

  const targetUsd = totalMarketValueUsd * (targetWeightPct / 100)
  const diffUsd = targetUsd - marketValueUsd
  const rawQty = lastPriceUsd > 0 ? diffUsd / lastPriceUsd : 0
  let signedQty = Math.trunc(rawQty)

  // 매도 cap: 보유 수량 초과 매도는 보유분으로 제한.
  let cappedBySellQty = false
  if (signedQty < 0 && Math.abs(signedQty) > currentQty) {
    signedQty = -Math.floor(currentQty)
    cappedBySellQty = true
  }

  const action: RebalanceAction = signedQty > 0 ? 'BUY' : signedQty < 0 ? 'SELL' : 'NONE'
  const absQty = Math.abs(signedQty)
  const costUsd = absQty * lastPriceUsd

  // 실현 비중·오차
  const realizedMarketValueUsd = marketValueUsd + signedQty * lastPriceUsd
  const realizedWeightPct =
    totalMarketValueUsd > 0 ? (realizedMarketValueUsd / totalMarketValueUsd) * 100 : 0
  const errorPct = targetWeightPct - realizedWeightPct

  // 매수 시 현금 차감. 매도 시 현금 증가.
  const postCashUsd =
    action === 'BUY' ? cashUsd - costUsd : action === 'SELL' ? cashUsd + costUsd : cashUsd

  const cashShortage = action === 'BUY' && postCashUsd < 0
  const maxAffordableQty =
    action === 'BUY' && lastPriceUsd > 0 ? Math.max(0, Math.floor(cashUsd / lastPriceUsd)) : 0

  return {
    action,
    qty: absQty,
    costUsd,
    realizedWeightPct,
    errorPct,
    postCashUsd,
    cashShortage,
    maxAffordableQty,
    cappedBySellQty,
    currentWeightPct,
  }
}

/**
 * PortfolioView + PositionView + targetWeightPct → RebalanceInput.
 * lastPriceUsd 가 null 이거나 0 이면 null 반환 (호출부는 종목 선택 단계에서 이미 걸러야 정상).
 */
export function buildRebalanceInput(
  portfolio: PortfolioView,
  position: PositionView,
  targetWeightPct: number,
): RebalanceInput | null {
  if (position.lastPriceUsd == null) return null
  const lastPriceUsd = Number(position.lastPriceUsd)
  if (!Number.isFinite(lastPriceUsd) || lastPriceUsd <= 0) return null
  const marketValueUsd = Number(position.marketValueUsd ?? '0')
  if (!Number.isFinite(marketValueUsd)) return null
  return {
    ticker: position.ticker,
    qty: Number(position.qty),
    lastPriceUsd,
    marketValueUsd,
    cashUsd: Number(portfolio.cashUsd),
    totalMarketValueUsd: Number(portfolio.totalMarketValueUsd),
    targetWeightPct,
  }
}
