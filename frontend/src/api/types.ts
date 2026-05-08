// 백엔드 DTO와 1:1 매칭. BigDecimal 필드는 모두 string으로 직렬화돼 오므로
// 표시 직전에만 Number()/Intl.NumberFormat 으로 변환한다.

export type Currency = 'USD' | 'KRW'

export type TradeType = 'DEPOSIT' | 'WITHDRAW' | 'BUY' | 'SELL' | 'DIVIDEND'

export type Exchange = 'NAS' | 'NYS' | 'AMS'

export interface PositionView {
  ticker: string
  qty: string
  avgCostUsd: string
  avgCostKrw: string
  realizedPnlUsd: string
  // 시세 조회 실패 시 아래 필드는 null
  lastPriceUsd: string | null
  lastPriceKrw: string | null
  marketValueUsd: string | null
  marketValueKrw: string | null
  weight: string | null
  unrealizedPnlUsd: string | null
  unrealizedPnlKrw: string | null
  // 시세는 잡혔지만 KIS 응답에 보조 필드가 누락된 경우도 가능 → 시세 가용 여부와 별도로 nullable.
  // dailyChangePct: % 단위 (예: "1.23" = +1.23%, "-4.56" = -4.56%)
  // weekRangeRatio: 0~1 (예: "0.6234")
  dailyChangePct: string | null
  weekHigh52Usd: string | null
  weekLow52Usd: string | null
  weekRangeRatio: string | null
}

export interface PortfolioView {
  cashUsd: string
  cashKrw: string
  cashWeight: string
  principalUsd: string
  principalKrw: string
  cumulativeDepositUsd: string
  cumulativeWithdrawUsd: string
  totalMarketValueUsd: string
  totalMarketValueKrw: string
  totalCostBasisUsd: string
  totalCostBasisKrw: string
  totalUnrealizedPnlUsd: string
  totalUnrealizedPnlKrw: string
  totalAssetsUsd: string
  totalAssetsKrw: string
  usdKrwRate: string
  asOf: string // ISO-8601 with KST offset
  positions: PositionView[]
  // 거래 부족·수렴 실패·순 원금 ≤ 0 인 경우 null. 비율(0.1250 = 12.50%)로 들어온다.
  irr: string | null
  simpleReturn: string | null
  // 시세 기준 시각 = 종목별 Quote.asOf 의 최솟값. ISO Z. 시세 0개일 때 null.
  quoteAsOf: string | null
  // 시세 성공 종목 수.
  quoteCount: number
  // 보유 종목 수 (시세 실패 포함).
  positionsCount: number
}

export interface SnapshotView {
  date: string // yyyy-MM-dd (KST)
  takenAt: string // ISO-8601 with KST offset
  usdKrwRate: string
  cashUsd: string
  cashKrw: string
  principalUsd: string
  principalKrw: string
  totalMarketValueUsd: string
  totalMarketValueKrw: string
  totalCostBasisUsd: string
  totalCostBasisKrw: string
  totalUnrealizedPnlUsd: string
  totalUnrealizedPnlKrw: string
  totalAssetsUsd: string
  totalAssetsKrw: string
}

export interface SnapshotListResponse {
  snapshots: SnapshotView[]
}

// FE-2 대비. JsonInclude.NON_NULL 이므로 일부 필드는 응답에 없을 수 있다.
export interface TradeView {
  tradeId: string
  type: TradeType
  executedAt: string // ISO Z
  ticker?: string
  qty?: string
  price?: string
  fee?: string
  cashAmount?: string
  // memo 는 DEPOSIT/WITHDRAW 거래에서만 입력 받는다 (UI 1차안). 백엔드 모델은 모든 종류에서 허용.
  memo?: string
  // SELL 거래만 채워진다. (매도가 - 매도 시점 평균단가) × 수량 - 수수료. 손익 페이지에서 사용.
  realizedPnlUsd?: string
}

export interface RecordTradeRequest {
  type: TradeType
  executedAt?: string
  ticker?: string
  qty?: string
  price?: string
  fee?: string
  cashAmount?: string
  memo?: string
}

export interface RecordTradeResponse {
  tradeId: string
  executedAt: string
  type: TradeType
}
