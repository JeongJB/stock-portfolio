// 백엔드 DTO와 1:1 매칭. BigDecimal 필드는 모두 string으로 직렬화돼 오므로
// 표시 직전에만 Number()/Intl.NumberFormat 으로 변환한다.

export type Currency = 'USD' | 'KRW'

export type TradeType = 'DEPOSIT' | 'WITHDRAW' | 'BUY' | 'SELL'

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
  usdKrwRate: string
  asOf: string // ISO-8601 with KST offset
  positions: PositionView[]
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
  positions: PositionView[]
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
}

export interface RecordTradeRequest {
  type: TradeType
  executedAt?: string
  ticker?: string
  qty?: string
  price?: string
  fee?: string
  cashAmount?: string
}

export interface RecordTradeResponse {
  tradeId: string
  executedAt: string
  type: TradeType
}
