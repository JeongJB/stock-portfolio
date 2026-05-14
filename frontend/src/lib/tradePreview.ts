// 거래 입력 중 "체결 후 비중 변화" 미리보기 계산 (pure function).
// 분모는 totalMarketValueUsd (현금 제외 주식 가치 합) — BUY/SELL 모두 같은 종목에 대해 분모가 변하므로
// `newTotalMV = totalMV - oldTickerMV + newTickerMV` 로 재계산.

import type { PositionView } from '../api/types'

export type PreviewTradeType = 'BUY' | 'SELL'

export interface TradePreviewInput {
  type: PreviewTradeType
  ticker: string
  qty: number
  price: number // BUY/SELL 거래 입력가 (USD). lastPriceUsd 가 없는 신규 종목에선 평가가로도 사용.
  currentPosition?: PositionView | null // 보유 종목 (없으면 신규)
  totalMarketValueUsd: number
}

export interface TradePreviewResult {
  isNewPosition: boolean // 신규 매수 (보유 0 → BUY) 여부
  currentWeightPct: number // 현재 비중 (% — 분모는 변경 전 totalMV). 신규면 0
  postWeightPct: number // 체결 후 비중 (% — 분모는 변경 후 totalMV)
  deltaPct: number // postWeightPct - currentWeightPct (부호 있음)
}

export interface InvalidPreview {
  invalid: true
  reason: 'INSUFFICIENT_INPUT' | 'OVERSELL'
}

/**
 * 비중 미리보기 계산. 입력이 부족하거나(qty/price 미입력), SELL 수량이 보유 초과면 invalid 반환.
 *
 * 평가가 결정:
 *  - 보유 종목: position.lastPriceUsd 우선, 없으면 입력 price
 *  - 신규 종목 (BUY only): 입력 price
 */
export function computeTradePreview(
  input: TradePreviewInput,
): TradePreviewResult | InvalidPreview {
  const { type, qty, price, currentPosition, totalMarketValueUsd } = input

  if (!Number.isFinite(qty) || qty <= 0) {
    return { invalid: true, reason: 'INSUFFICIENT_INPUT' }
  }
  if (!Number.isFinite(price) || price <= 0) {
    // 신규 종목인데 가격도 없으면 평가 불가
    if (currentPosition == null || currentPosition.lastPriceUsd == null) {
      return { invalid: true, reason: 'INSUFFICIENT_INPUT' }
    }
  }

  const currentQty = currentPosition ? Number(currentPosition.qty) : 0
  const isNewPosition = type === 'BUY' && (!currentPosition || currentQty === 0)

  // 평가가: 보유 종목이면 시세 우선 (없으면 입력 price), 신규면 입력 price.
  const evalPrice =
    currentPosition && currentPosition.lastPriceUsd != null
      ? Number(currentPosition.lastPriceUsd)
      : price

  if (!Number.isFinite(evalPrice) || evalPrice <= 0) {
    return { invalid: true, reason: 'INSUFFICIENT_INPUT' }
  }

  const signedQty = type === 'BUY' ? qty : -qty
  const newQty = currentQty + signedQty
  if (newQty < 0) {
    return { invalid: true, reason: 'OVERSELL' }
  }

  const oldTickerMV = currentQty * evalPrice
  const newTickerMV = newQty * evalPrice
  const newTotalMV = totalMarketValueUsd - oldTickerMV + newTickerMV

  const currentWeightPct =
    totalMarketValueUsd > 0 ? (oldTickerMV / totalMarketValueUsd) * 100 : 0
  const postWeightPct = newTotalMV > 0 ? (newTickerMV / newTotalMV) * 100 : 0
  const deltaPct = postWeightPct - currentWeightPct

  return {
    isNewPosition,
    currentWeightPct,
    postWeightPct,
    deltaPct,
  }
}

export function isInvalidPreview(
  result: TradePreviewResult | InvalidPreview,
): result is InvalidPreview {
  return 'invalid' in result && result.invalid === true
}
