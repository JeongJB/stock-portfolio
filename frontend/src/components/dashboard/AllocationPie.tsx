import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import type { PortfolioView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import { formatMoney, formatPercent } from '../../app/format'

interface Props {
  data: PortfolioView
}

interface Slice {
  name: string
  weight: number
  marketValue: string | null
}

// Tailwind 팔레트 인접 색상. 슬라이스가 늘어도 cycle 되도록.
const COLORS = [
  '#2563eb',
  '#16a34a',
  '#f59e0b',
  '#ef4444',
  '#a855f7',
  '#0891b2',
  '#db2777',
  '#65a30d',
  '#7c3aed',
  '#ea580c',
]

export function AllocationPie({ data }: Props) {
  const { currency } = useCurrency()

  // 시세 실패 종목(weight == null)은 제외.
  const positionSlices: Slice[] = data.positions
    .filter((p) => p.weight != null && Number(p.weight) > 0)
    .map((p) => ({
      name: p.ticker,
      weight: Number(p.weight),
      marketValue: currency === 'USD' ? p.marketValueUsd : p.marketValueKrw,
    }))

  const cashWeight = Number(data.cashWeight)
  const cashSlice: Slice | null =
    Number.isFinite(cashWeight) && cashWeight > 0
      ? {
          name: 'USD 현금',
          weight: cashWeight,
          marketValue: currency === 'USD' ? data.cashUsd : data.cashKrw,
        }
      : null

  const slices = [...positionSlices, ...(cashSlice ? [cashSlice] : [])].sort(
    (a, b) => b.weight - a.weight,
  )

  if (slices.length === 0) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
        표시할 비중 데이터가 없습니다. (시세 조회가 모두 실패했거나 보유 자산이 없습니다.)
      </section>
    )
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <h3 className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">자산 비중</h3>
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
        <div className="h-72 flex-1">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart margin={{ top: 8, right: 16, bottom: 8, left: 16 }}>
              <Pie
                data={slices}
                dataKey="weight"
                nameKey="name"
                cx="50%"
                cy="50%"
                outerRadius="80%"
                label={false}
                labelLine={false}
                isAnimationActive={false}
              >
                {slices.map((s, idx) => (
                  <Cell key={s.name} fill={COLORS[idx % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip
                formatter={(value, _name, item) => {
                  const slice = (item as { payload?: Slice } | undefined)?.payload
                  const pct = formatPercent(String(value ?? ''))
                  const mv = slice ? formatMoney(slice.marketValue, currency) : '—'
                  return [`${pct} · ${mv}`, slice?.name ?? '']
                }}
              />
            </PieChart>
          </ResponsiveContainer>
        </div>
        <AllocationLegend slices={slices} />
      </div>
    </section>
  )
}

interface LegendProps {
  slices: Slice[]
}

function AllocationLegend({ slices }: LegendProps) {
  return (
    <ul className="flex flex-col gap-1.5 text-sm lg:w-1/3">
      {slices.map((s, idx) => (
        <li
          key={s.name}
          className="flex items-center gap-2 text-slate-700 dark:text-slate-200"
        >
          <span
            className="inline-block h-3 w-3 shrink-0 rounded-sm"
            style={{ backgroundColor: COLORS[idx % COLORS.length] }}
            aria-hidden
          />
          <span className="flex-1 truncate">{s.name}</span>
          <span className="tabular-nums text-slate-600 dark:text-slate-300">
            {formatPercent(String(s.weight))}
          </span>
        </li>
      ))}
    </ul>
  )
}
