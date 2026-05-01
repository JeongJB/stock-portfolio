import { formatSignedMoney, pnlColorClass } from '../../app/format'

export interface PnlRow {
  // 표시용 라벨 ("2026-04" / "2026" 등). groupBy 결과 키.
  period: string
  realizedUsd: number
  dividendUsd: number
  totalUsd: number
}

interface Props {
  rows: PnlRow[]
}

export function PnlTable({ rows }: Props) {
  if (rows.length === 0) {
    return (
      <section className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/40 dark:text-slate-300">
        선택한 기간에 표시할 손익이 없습니다.
      </section>
    )
  }

  return (
    <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-800 dark:text-slate-400">
          <tr>
            <Th align="left" className="sticky left-0 z-10 bg-slate-50 dark:bg-slate-800">
              기간
            </Th>
            <Th>실현 손익 (USD)</Th>
            <Th>배당 (USD)</Th>
            <Th>합계 (USD)</Th>
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
              <Td className={pnlColorClass(String(r.realizedUsd))}>
                {formatSignedMoney(String(r.realizedUsd), 'USD')}
              </Td>
              <Td className={pnlColorClass(String(r.dividendUsd))}>
                {formatSignedMoney(String(r.dividendUsd), 'USD')}
              </Td>
              <Td className={`${pnlColorClass(String(r.totalUsd))} font-medium`}>
                {formatSignedMoney(String(r.totalUsd), 'USD')}
              </Td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
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
