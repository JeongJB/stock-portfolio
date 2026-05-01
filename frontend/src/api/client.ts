import type {
  PortfolioView,
  RecordTradeRequest,
  RecordTradeResponse,
  SnapshotListResponse,
  SnapshotView,
  TradeView,
} from './types'

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '') as string
const API_KEY = (import.meta.env.VITE_API_KEY ?? '') as string

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (API_KEY) headers.set('x-api-key', API_KEY)

  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers })
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

export function listSnapshots(from?: string, to?: string): Promise<SnapshotListResponse> {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const qs = params.toString()
  return request<SnapshotListResponse>(`/api/snapshots${qs ? `?${qs}` : ''}`)
}
