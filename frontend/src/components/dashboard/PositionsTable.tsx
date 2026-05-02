import type { PositionView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import {
  formatMoney,
  formatPercent,
  formatQty,
  formatSignedMoney,
  pnlColorClass,
} from '../../app/format'
import { WeekRangeBar } from './WeekRangeBar'

interface Props {
  positions: PositionView[]
}

export function PositionsTable({ positions }: Props) {
  const { currency } = useCurrency()
  const unit = currency === 'USD' ? 'USD' : 'KRW'

  // 평가액 내림차순. null 행은 맨 뒤(qty 보조 정렬).
  const sorted = [...positions].sort((a, b) => {
    const aMv = currency === 'USD' ? a.marketValueUsd : a.marketValueKrw
    const bMv = currency === 'USD' ? b.marketValueUsd : b.marketValueKrw
    if (aMv == null && bMv == null) return 0
    if (aMv == null) return 1
    if (bMv == null) return -1
    return Number(bMv) - Number(aMv)
  })

  if (sorted.length === 0) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
        보유 종목이 없습니다.
      </section>
    )
  }

  return (
    <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-800 dark:text-slate-400">
          <tr>
            <Th align="left">티커</Th>
            <Th>수량</Th>
            <Th>평단 ({unit})</Th>
            <Th>현재가 ({unit})</Th>
            <Th>당일</Th>
            <Th>52주 위치</Th>
            <Th>평가액 ({unit})</Th>
            <Th>비중</Th>
            <Th>평가손익 ({unit})</Th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {sorted.map((p) => (
            <Row key={p.ticker} position={p} currency={currency} />
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Row({
  position,
  currency,
}: {
  position: PositionView
  currency: 'USD' | 'KRW'
}) {
  const isUsd = currency === 'USD'
  const avgCost = isUsd ? position.avgCostUsd : position.avgCostKrw
  const lastPrice = isUsd ? position.lastPriceUsd : position.lastPriceKrw
  const marketValue = isUsd ? position.marketValueUsd : position.marketValueKrw
  const pnl = isUsd ? position.unrealizedPnlUsd : position.unrealizedPnlKrw
  const quoteFailed = position.lastPriceUsd == null

  const rowClass = quoteFailed
    ? 'bg-slate-100 dark:bg-slate-800/50 text-slate-500 dark:text-slate-400'
    : ''

  return (
    <tr className={rowClass}>
      <Td align="left" className="font-medium">
        {position.ticker}
        {quoteFailed && (
          <span
            className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-normal text-amber-800 dark:bg-amber-900/40 dark:text-amber-300"
            title="시세 조회 실패"
          >
            시세 없음
          </span>
        )}
      </Td>
      <Td>{formatQty(position.qty)}</Td>
      <Td>{formatMoney(avgCost, currency)}</Td>
      <Td>{quoteFailed ? '—' : formatMoney(lastPrice, currency)}</Td>
      <Td className={changePctColorClass(position.dailyChangePct)}>
        {formatChangePct(position.dailyChangePct)}
      </Td>
      <Td className="min-w-[140px]">
        <WeekRangeBar
          low={position.weekLow52Usd}
          high={position.weekHigh52Usd}
          current={position.lastPriceUsd}
          ratio={position.weekRangeRatio}
        />
      </Td>
      <Td>{quoteFailed ? '—' : formatMoney(marketValue, currency)}</Td>
      <Td>{quoteFailed ? '—' : formatPercent(position.weight)}</Td>
      <Td className={quoteFailed ? '' : pnlColorClass(pnl)}>
        {quoteFailed ? '—' : formatSignedMoney(pnl, currency)}
      </Td>
    </tr>
  )
}

/**
 * 등락률 표시: "+1.23%" / "-4.56%" / "0.00%" / "—".
 * 이미 % 단위로 들어오므로 (예: "1.23" = 1.23%) Intl.NumberFormat percent 미적용.
 */
function formatChangePct(value: string | null | undefined): string {
  if (value == null || value === '') return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return '—'
  const sign = n > 0 ? '+' : n < 0 ? '-' : ''
  return `${sign}${Math.abs(n).toFixed(2)}%`
}

/**
 * 등락률 색상: |값| >= 0.005 (≈ ±0.01% 반올림 임계) → 초록/빨강, 그 외 회색.
 * 보합(0)·null·미세값은 정보 손실 없이 회색으로 보합 표시.
 */
function changePctColorClass(value: string | null | undefined): string {
  if (value == null || value === '') return 'text-slate-500 dark:text-slate-400'
  const n = Number(value)
  if (Number.isNaN(n)) return 'text-slate-500 dark:text-slate-400'
  if (n >= 0.005) return 'text-emerald-600 dark:text-emerald-400'
  if (n <= -0.005) return 'text-rose-600 dark:text-rose-400'
  return 'text-slate-500 dark:text-slate-400'
}

function Th({
  children,
  align = 'right',
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
}) {
  return (
    <th
      scope="col"
      className={`whitespace-nowrap px-3 py-2 ${align === 'left' ? 'text-left' : 'text-right'}`}
    >
      {children}
    </th>
  )
}

function Td({
  children,
  align = 'right',
  className = '',
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
  className?: string
}) {
  return (
    <td
      className={`whitespace-nowrap px-3 py-2 tabular-nums ${align === 'left' ? 'text-left' : 'text-right'} ${className}`}
    >
      {children}
    </td>
  )
}
