// 비중 리밸런싱 계산기 (pure function).
// 분모는 호출부가 선택 — 'EX_CASH' (주식 가치 합) 또는 'INC_CASH' (주식 + 현금).
// 입력은 모두 number 로 받는다 — 호출부에서 BigDecimal string → Number 변환 책임.

import type { PortfolioView, PositionView } from '../api/types'

export type RebalanceAction = 'BUY' | 'SELL' | 'NONE'
export type BasisMode = 'EX_CASH' | 'INC_CASH'

export interface RebalanceInput {
  ticker: string
  qty: number // 현재 보유 수량
  lastPriceUsd: number
  marketValueUsd: number
  cashUsd: number
  // 비중 계산 분모 — 호출부가 BasisMode 에 따라 주식 합 or 주식+현금 으로 채움.
  denominatorUsd: number
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
 * 목표 비중에 가장 가까운 정수 주를 선택한다 (round, half-away-from-zero).
 * 예: target 2%, 한 주당 0.7% 인 경우 rawQty = 2.857... → 3주 매수 (실현 2.1%, 오차 -0.1%).
 * truncate 였다면 2주 매수 (실현 1.4%, 오차 +0.6%) 라 더 멀었음.
 * 필요주수 0 이면 NONE.
 */
export function computeRebalance(input: RebalanceInput): RebalanceResult {
  const {
    qty: currentQty,
    lastPriceUsd,
    marketValueUsd,
    cashUsd,
    denominatorUsd,
    targetWeightPct,
  } = input

  const currentWeightPct = denominatorUsd > 0 ? (marketValueUsd / denominatorUsd) * 100 : 0

  const targetUsd = denominatorUsd * (targetWeightPct / 100)
  const diffUsd = targetUsd - marketValueUsd
  const rawQty = lastPriceUsd > 0 ? diffUsd / lastPriceUsd : 0
  // half-away-from-zero rounding — 음수 rawQty 에 대해서도 절대값 기준 반올림이라 일관성 있다.
  // JavaScript Math.round 는 음수 0.5 에서 half-toward-positive-infinity 라 직접 sign-aware 처리.
  let signedQty = Math.sign(rawQty) * Math.round(Math.abs(rawQty))

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
    denominatorUsd > 0 ? (realizedMarketValueUsd / denominatorUsd) * 100 : 0
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
 * PortfolioView + PositionView + targetWeightPct + basisMode → RebalanceInput.
 * lastPriceUsd 가 null 이거나 0 이면 null 반환 (호출부는 종목 선택 단계에서 이미 걸러야 정상).
 * basisMode='EX_CASH': 분모 = 주식 가치 합. 'INC_CASH': 분모 = 주식 + 현금.
 */
export function buildRebalanceInput(
  portfolio: PortfolioView,
  position: PositionView,
  targetWeightPct: number,
  basisMode: BasisMode,
): RebalanceInput | null {
  if (position.lastPriceUsd == null) return null
  const lastPriceUsd = Number(position.lastPriceUsd)
  if (!Number.isFinite(lastPriceUsd) || lastPriceUsd <= 0) return null
  const marketValueUsd = Number(position.marketValueUsd ?? '0')
  if (!Number.isFinite(marketValueUsd)) return null
  const cashUsd = Number(portfolio.cashUsd)
  const totalMarketValueUsd = Number(portfolio.totalMarketValueUsd)
  const denominatorUsd =
    basisMode === 'INC_CASH' ? totalMarketValueUsd + cashUsd : totalMarketValueUsd
  return {
    ticker: position.ticker,
    qty: Number(position.qty),
    lastPriceUsd,
    marketValueUsd,
    cashUsd,
    denominatorUsd,
    targetWeightPct,
  }
}
