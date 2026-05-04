import type { Currency } from '../../api/types'
import { formatSignedMoney, pnlColorClass } from '../../app/format'

export interface PnlRow {
  // 표시용 라벨 ("2026-04" / "2026" 등). groupBy 결과 키.
  period: string
  // 통화 환산 결과를 string 으로 보관해 KRW 환산 실패(환율 null) 케이스에서
  // 셀을 — 로 fallback 시킬 수 있게 한다. 정상 값은 일반 BigDecimal-스러운 문자열.
  realized: string | null
  dividend: string | null
  total: string | null
}

interface Props {
  rows: PnlRow[]
  currency: Currency
}

export function PnlTable({ rows, currency }: Props) {
  if (rows.length === 0) {
    return (
      <section className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/40 dark:text-slate-300">
        선택한 기간에 표시할 손익이 없습니다.
      </section>
    )
  }

  // 합계 행 — 모든 표시 기간의 산술합. 환산 실패 셀이 하나라도 섞여 있으면 합도 — 로 fallback.
  const totals = computeTotals(rows)
  const unitLabel = currency === 'USD' ? 'USD' : 'KRW'

  return (
    <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-800 dark:text-slate-400">
          <tr>
            <Th align="left" className="sticky left-0 z-10 bg-slate-50 dark:bg-slate-800">
              기간
            </Th>
            <Th>실현 손익 ({unitLabel})</Th>
            <Th>배당 ({unitLabel})</Th>
            <Th>합계 ({unitLabel})</Th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {rows.map((r) => (
            <tr key={r.period}>
              <Td
                align="left"
                className="sticky left-0 z-10 bg-white font-medium text-slate-700 dark:bg-slate-900 dark:text-slate-200"
              >
                {r.period}
              </Td>
              <Td className={pnlColorClass(r.realized)}>
                {formatSignedMoney(r.realized, currency)}
              </Td>
              <Td className={pnlColorClass(r.dividend)}>
                {formatSignedMoney(r.dividend, currency)}
              </Td>
              <Td className={`${pnlColorClass(r.total)} font-medium`}>
                {formatSignedMoney(r.total, currency)}
              </Td>
            </tr>
          ))}
          <tr className="border-t-2 border-slate-300 bg-slate-50 dark:border-slate-700 dark:bg-slate-800/60">
            <Td
              align="left"
              className="sticky left-0 z-10 bg-slate-50 font-semibold text-slate-700 dark:bg-slate-800/60 dark:text-slate-200"
            >
              합계
            </Td>
            <Td className={`${pnlColorClass(totals.realized)} font-semibold`}>
              {formatSignedMoney(totals.realized, currency)}
            </Td>
            <Td className={`${pnlColorClass(totals.dividend)} font-semibold`}>
              {formatSignedMoney(totals.dividend, currency)}
            </Td>
            <Td className={`${pnlColorClass(totals.total)} font-semibold`}>
              {formatSignedMoney(totals.total, currency)}
            </Td>
          </tr>
        </tbody>
      </table>
    </section>
  )
}

interface Totals {
  realized: string | null
  dividend: string | null
  total: string | null
}

function computeTotals(rows: PnlRow[]): Totals {
  return {
    realized: sumOrNull(rows.map((r) => r.realized)),
    dividend: sumOrNull(rows.map((r) => r.dividend)),
    total: sumOrNull(rows.map((r) => r.total)),
  }
}

// 모든 값이 유효 number 면 합산해 문자열로 반환. 하나라도 null 이면 null.
function sumOrNull(values: (string | null)[]): string | null {
  let acc = 0
  for (const v of values) {
    if (v == null) return null
    const n = Number(v)
    if (!Number.isFinite(n)) return null
    acc += n
  }
  return String(acc)
}

function Th({
  children,
  align = 'right',
  className = '',
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
  className?: string
}) {
  return (
    <th
      scope="col"
      className={`whitespace-nowrap px-3 py-2 ${align === 'left' ? 'text-left' : 'text-right'} ${className}`}
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
