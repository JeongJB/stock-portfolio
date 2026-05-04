import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getPortfolio, listTrades } from '../api/client'
import type { Currency, TradeView } from '../api/types'
import { useCurrency } from '../app/currencyContext'
import { formatRate } from '../app/format'
import { PeriodSelector } from '../components/snapshots/PeriodSelector'
import { PnlTable } from '../components/pnl/PnlTable'
import type { PnlRow } from '../components/pnl/PnlTable'
import { PnlUnitToggle } from '../components/pnl/PnlUnitToggle'
import { usePnlPeriod } from '../components/pnl/usePnlPeriod'
import { usePnlUnit } from '../components/pnl/usePnlUnit'
import type { PnlUnit } from '../components/pnl/usePnlUnit'

// 거래 limit — 손익 페이지는 장기간 합산이 필요하므로 백엔드 최대치를 사용한다.
const TRADES_LIMIT = 1000

const KST_GROUP_FMT_MONTH = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
})

const KST_GROUP_FMT_YEAR = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
})

export function Pnl() {
  const { state, setPreset, setCustomFrom, setCustomTo, range } = usePnlPeriod()
  const { unit, setUnit } = usePnlUnit()
  const { currency } = useCurrency()

  // 거래 내역 페이지와 limit 가 다르므로 캐시 키를 분리한다 (200 vs 1000).
  const tradesQuery = useQuery({
    queryKey: ['trades-pnl', TRADES_LIMIT],
    queryFn: () => listTrades(TRADES_LIMIT),
  })
  // Dashboard 와 동일 키 — react-query 가 캐시를 공유해 추가 호출이 발생하지 않는다.
  // KRW 환산용 환율 1개만 필요해 select 로 잘라 리렌더 비용도 최소화.
  const portfolioQuery = useQuery({
    queryKey: ['portfolio'],
    queryFn: getPortfolio,
    select: (v) => v.usdKrwRate,
  })

  const usdKrwRate = portfolioQuery.data ?? null
  const rateNum = usdKrwRate == null ? null : Number(usdKrwRate)
  const rateValid = rateNum != null && Number.isFinite(rateNum) && rateNum > 0

  const rows = useMemo<PnlRow[]>(() => {
    if (!tradesQuery.data) return []
    return groupTrades(tradesQuery.data, range.from, range.to, unit, currency, usdKrwRate)
  }, [tradesQuery.data, range.from, range.to, unit, currency, usdKrwRate])

  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h2 className="text-xl font-medium">손익</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          기간 내 매도 실현 손익과 배당을 월별/연별로 집계합니다. USD 와 응답 시점 환율 기반
          KRW 환산 중 상단 통화 토글에 따라 한 쪽을 표시합니다.
        </p>
      </div>

      <PeriodSelector
        state={state}
        onPresetChange={setPreset}
        onCustomFromChange={setCustomFrom}
        onCustomToChange={setCustomTo}
      />

      <PnlUnitToggle unit={unit} onChange={setUnit} />

      {currency === 'KRW' && <RateHeader rate={usdKrwRate} valid={rateValid} />}

      {tradesQuery.isPending && <PnlSkeleton />}

      {tradesQuery.isError && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
          <p className="mb-2">
            거래 내역을 불러오지 못했습니다: {(tradesQuery.error as Error).message}
          </p>
          <button
            type="button"
            onClick={() => tradesQuery.refetch()}
            className="rounded border border-rose-300 px-3 py-1 text-xs hover:bg-rose-100 dark:border-rose-800 dark:hover:bg-rose-900/40"
          >
            재시도
          </button>
        </div>
      )}

      {tradesQuery.isSuccess && <PnlTable rows={rows} currency={currency} />}
    </section>
  )
}

function RateHeader({ rate, valid }: { rate: string | null; valid: boolean }) {
  const tooltip =
    '응답 시점 환율로 일괄 환산. 거래 시점 환율과 차이가 있을 수 있어 ' +
    '정확한 KRW 신고 환산은 매매기준율을 사용해야 합니다.'
  if (!valid) {
    return (
      <p className="text-xs text-slate-500 dark:text-slate-400" title={tooltip}>
        환산 환율: <span className="text-slate-700 dark:text-slate-200">— (조회 실패)</span>
      </p>
    )
  }
  return (
    <p className="text-xs text-slate-500 dark:text-slate-400" title={tooltip}>
      환산 환율:{' '}
      <span className="text-slate-700 dark:text-slate-200">
        {formatRate(rate)} KRW/USD (응답 시점, 참고치)
      </span>
    </p>
  )
}

function PnlSkeleton() {
  return (
    <div className="space-y-2">
      <div className="h-64 animate-pulse rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800" />
      <p className="text-xs text-slate-500 dark:text-slate-400">로딩 중...</p>
    </div>
  )
}

/**
 * 거래 목록을 [from, to+1day) 윈도로 필터해 KST 기준 월/연 단위로 그룹.
 * 각 그룹마다 SELL.realizedPnlUsd 와 DIVIDEND.cashAmount 합산.
 *
 *  - currency === 'USD' 면 USD 합계를 그대로 string 으로 반환.
 *  - currency === 'KRW' 면 USD 합계 × usdKrwRate 를 셀에 싣는다.
 *  - usdKrwRate 가 null/0/NaN 이면 KRW 모드 셀은 모두 null → 표는 '—' fallback.
 *
 * 결과는 최신 기간이 위로 오도록 내림차순 정렬.
 */
export function groupTrades(
  trades: TradeView[],
  from: string,
  to: string,
  unit: PnlUnit,
  currency: Currency,
  usdKrwRate: string | null,
): PnlRow[] {
  // PeriodSelector 의 to 는 inclusive day. 비교는 Date 변환 후 (to + 1day) 미만으로.
  const fromMs = Date.parse(`${from}T00:00:00+09:00`)
  // KST 기준 to 의 다음 날 자정 (exclusive upper bound)
  const toExclusiveMs = Date.parse(`${to}T00:00:00+09:00`) + 24 * 3600 * 1000
  if (!Number.isFinite(fromMs) || !Number.isFinite(toExclusiveMs)) return []

  const fmt = unit === 'monthly' ? KST_GROUP_FMT_MONTH : KST_GROUP_FMT_YEAR
  const buckets = new Map<string, { realized: number; dividend: number }>()

  for (const t of trades) {
    const ts = Date.parse(t.executedAt)
    if (!Number.isFinite(ts)) continue
    if (ts < fromMs || ts >= toExclusiveMs) continue
    if (t.type !== 'SELL' && t.type !== 'DIVIDEND') continue

    const key = fmt.format(new Date(ts))
    const bucket = buckets.get(key) ?? { realized: 0, dividend: 0 }
    if (t.type === 'SELL') {
      const v = Number(t.realizedPnlUsd ?? '0')
      if (Number.isFinite(v)) bucket.realized += v
    } else {
      const v = Number(t.cashAmount ?? '0')
      if (Number.isFinite(v)) bucket.dividend += v
    }
    buckets.set(key, bucket)
  }

  const rateNum = usdKrwRate == null ? null : Number(usdKrwRate)
  const rateValid = rateNum != null && Number.isFinite(rateNum) && rateNum > 0
  const convert = (usd: number): string | null => {
    if (currency === 'USD') return String(usd)
    if (!rateValid) return null
    return String(usd * (rateNum as number))
  }

  const rows: PnlRow[] = []
  for (const [period, { realized, dividend }] of buckets.entries()) {
    rows.push({
      period,
      realized: convert(realized),
      dividend: convert(dividend),
      total: convert(realized + dividend),
    })
  }
  // en-CA "yyyy-MM" 또는 "yyyy" 라 문자열 내림차순 = 최신 우선.
  rows.sort((a, b) => (a.period < b.period ? 1 : a.period > b.period ? -1 : 0))
  return rows
}
