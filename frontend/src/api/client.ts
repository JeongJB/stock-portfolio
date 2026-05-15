import type {
  PortfolioView,
  RecordTradeRequest,
  RecordTradeResponse,
  SnapshotListResponse,
  SnapshotView,
  TradeView,
} from './types'

// same-origin (CloudFront /api/* 라우팅) 이라 절대 URL 불필요 — relative path 그대로.
// dev 환경은 vite proxy (/api → :8080) 가 같은 origin 으로 보이게 만들어준다.
const API_KEY = (import.meta.env.VITE_API_KEY ?? '') as string

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (API_KEY) headers.set('x-api-key', API_KEY)

  const res = await fetch(path, { ...init, headers })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`HTTP ${res.status} ${res.statusText}${text ? `: ${text}` : ''}`)
  }
  // 204 No Content 등을 대비
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export function getPortfolio(): Promise<PortfolioView> {
  return request<PortfolioView>('/api/portfolio')
}

export function recordTrade(req: RecordTradeRequest): Promise<RecordTradeResponse> {
  return request<RecordTradeResponse>('/api/trades', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function takeSnapshot(): Promise<SnapshotView> {
  return request<SnapshotView>('/api/snapshots', { method: 'POST' })
}

export function listTrades(limit = 200): Promise<TradeView[]> {
  return request<TradeView[]>(`/api/trades?limit=${limit}`)
}

// 응답 본문(갱신된 PortfolioView) 은 useQuery invalidate 로 다시 불러올 거라 무시한다.
export async function deleteTrade(id: string): Promise<void> {
  await request<unknown>(`/api/trades/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function listSnapshots(from?: string, to?: string): Promise<SnapshotListResponse> {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const qs = params.toString()
  return request<SnapshotListResponse>(`/api/snapshots${qs ? `?${qs}` : ''}`)
}

// P1-9 보강: 보유 종목의 sector 단일 변경. sector=null 또는 빈 문자열은 분류 제거를 의미한다.
// 응답: { ticker: "AAPL", sector: "Big Tech" | null }
export interface UpdateSectorResponse {
  ticker: string
  sector: string | null
}

export function updateSector(
  ticker: string,
  sector: string | null,
): Promise<UpdateSectorResponse> {
  return request<UpdateSectorResponse>(
    `/api/positions/${encodeURIComponent(ticker)}/sector`,
    {
      method: 'PATCH',
      body: JSON.stringify({ sector }),
    },
  )
}
