import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listTrades } from '../api/client'
import type { TradeView } from '../api/types'
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

  // 거래 내역 페이지와 limit 가 다르므로 캐시 키를 분리한다 (200 vs 1000).
  const query = useQuery({
    queryKey: ['trades-pnl', TRADES_LIMIT],
    queryFn: () => listTrades(TRADES_LIMIT),
  })

  const rows = useMemo<PnlRow[]>(() => {
    if (!query.data) return []
    return groupTrades(query.data, range.from, range.to, unit)
  }, [query.data, range.from, range.to, unit])

  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h2 className="text-xl font-medium">손익</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          기간 내 매도 실현 손익과 배당을 월별/연별로 집계합니다. USD 기준으로만
          표시됩니다 (거래 시점 환율을 박제하지 않음).
        </p>
      </div>

      <PeriodSelector
        state={state}
        onPresetChange={setPreset}
        onCustomFromChange={setCustomFrom}
        onCustomToChange={setCustomTo}
      />

      <PnlUnitToggle unit={unit} onChange={setUnit} />

      {query.isPending && <PnlSkeleton />}

      {query.isError && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
          <p className="mb-2">
            거래 내역을 불러오지 못했습니다: {(query.error as Error).message}
          </p>
          <button
            type="button"
            onClick={() => query.refetch()}
            className="rounded border border-rose-300 px-3 py-1 text-xs hover:bg-rose-100 dark:border-rose-800 dark:hover:bg-rose-900/40"
          >
            재시도
          </button>
        </div>
      )}

      {query.isSuccess && <PnlTable rows={rows} />}
    </section>
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
 * 결과는 최신 기간이 위로 오도록 내림차순 정렬.
 */
function groupTrades(
  trades: TradeView[],
  from: string,
  to: string,
  unit: PnlUnit,
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

  const rows: PnlRow[] = []
  for (const [period, { realized, dividend }] of buckets.entries()) {
    rows.push({
      period,
      realizedUsd: realized,
      dividendUsd: dividend,
      totalUsd: realized + dividend,
    })
  }
  // en-CA "yyyy-MM" 또는 "yyyy" 라 문자열 내림차순 = 최신 우선.
  rows.sort((a, b) => (a.period < b.period ? 1 : a.period > b.period ? -1 : 0))
  return rows
}
