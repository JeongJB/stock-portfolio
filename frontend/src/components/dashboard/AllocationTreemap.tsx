import { ResponsiveContainer, Tooltip, Treemap } from 'recharts'
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

export function AllocationTreemap({ data }: Props) {
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

  const treemapData = slices.map((s, idx) => ({
    ...s,
    color: COLORS[idx % COLORS.length],
  }))

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <h3 className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">자산 비중</h3>
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
        <div className="h-72 w-full lg:flex-1">
          <ResponsiveContainer width="100%" height="100%">
            <Treemap
              data={treemapData}
              dataKey="weight"
              nameKey="name"
              stroke="#fff"
              isAnimationActive={false}
              content={<TreemapCell />}
            >
              <Tooltip
                formatter={(value, _name, item) => {
                  const slice = (item as { payload?: Slice } | undefined)?.payload
                  const pct = formatPercent(String(value ?? ''))
                  const mv = slice ? formatMoney(slice.marketValue, currency) : '—'
                  return [`${pct} · ${mv}`, slice?.name ?? '']
                }}
              />
            </Treemap>
          </ResponsiveContainer>
        </div>
        <AllocationLegend slices={slices} />
      </div>
    </section>
  )
}

interface TreemapCellProps {
  x?: number
  y?: number
  width?: number
  height?: number
  depth?: number
  name?: string
  weight?: number
  color?: string
}

function TreemapCell(props: TreemapCellProps) {
  const { x = 0, y = 0, width = 0, height = 0, depth, name, weight, color } = props
  if (depth !== 1 || width <= 0 || height <= 0) {
    return null
  }
  const showLabel = width > 44 && height > 22
  const showPct = width > 56 && height > 38
  const fill = color ?? '#2563eb'
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        fill={fill}
        stroke="#fff"
        strokeWidth={2}
      />
      {showLabel && name ? (
        <text
          x={x + width / 2}
          y={y + height / 2 - (showPct ? 7 : 0)}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#fff"
          fontSize={12}
          fontWeight={600}
          style={{ pointerEvents: 'none' }}
        >
          {name}
        </text>
      ) : null}
      {showPct && weight != null ? (
        <text
          x={x + width / 2}
          y={y + height / 2 + 9}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#fff"
          fontSize={10}
          opacity={0.85}
          style={{ pointerEvents: 'none' }}
        >
          {formatPercent(String(weight))}
        </text>
      ) : null}
    </g>
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
