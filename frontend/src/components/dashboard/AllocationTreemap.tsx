import { useEffect, useState } from 'react'
import { ResponsiveContainer, Tooltip, Treemap } from 'recharts'
import type { PortfolioView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import { formatMoney, formatPercent } from '../../app/format'
import { sectorHsl } from '../../app/sectorColor'

interface Props {
  data: PortfolioView
}

interface Slice {
  name: string
  weight: number
  marketValue: string | null
  // sector 모드에서만 채워짐 — 툴팁 2 줄용.
  tickerCount?: number
}

// 종목별 모드: 15 항목까지 인접 셀 구분이 잘 되도록 큐레이팅한 Tailwind 600/700 톤.
const TICKER_COLORS = [
  '#2563eb', // blue-600
  '#059669', // emerald-600
  '#d97706', // amber-600
  '#dc2626', // red-600
  '#7c3aed', // violet-600
  '#0891b2', // cyan-600
  '#db2777', // pink-600
  '#65a30d', // lime-600
  '#ea580c', // orange-600
  '#0d9488', // teal-600
  '#4f46e5', // indigo-600
  '#c026d3', // fuchsia-600
  '#a16207', // yellow-700
  '#be123c', // rose-700
  '#475569', // slate-600
]

type AllocationMode = 'ticker' | 'sector'

const ALLOCATION_MODE_KEY = 'allocation-mode'
const SECTOR_UNCLASSIFIED = '분류 미지정'
const CASH_LABEL_TICKER = 'USD 현금'
const CASH_LABEL_SECTOR = '현금'

function loadInitialMode(): AllocationMode {
  if (typeof window === 'undefined') return 'ticker'
  try {
    const v = window.localStorage.getItem(ALLOCATION_MODE_KEY)
    return v === 'sector' ? 'sector' : 'ticker'
  } catch {
    return 'ticker'
  }
}

export function AllocationTreemap({ data }: Props) {
  const { currency } = useCurrency()
  const [mode, setMode] = useState<AllocationMode>(loadInitialMode)

  useEffect(() => {
    try {
      window.localStorage.setItem(ALLOCATION_MODE_KEY, mode)
    } catch {
      // localStorage 비활성화 환경 — 무시.
    }
  }, [mode])

  const slices = mode === 'sector' ? buildSectorSlices(data, currency) : buildTickerSlices(data, currency)

  if (slices.length === 0) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
        <Header mode={mode} onChange={setMode} />
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          표시할 비중 데이터가 없습니다. (시세 조회가 모두 실패했거나 보유 자산이 없습니다.)
        </p>
      </section>
    )
  }

  // 색상은 mode 별로 다른 규칙 — ticker 모드는 인덱스 기반 큐레이션, sector 모드는 hash 기반 결정적 HSL.
  const treemapData = slices.map((s, idx) => ({
    ...s,
    color: mode === 'sector' ? sectorHsl(s.name) : TICKER_COLORS[idx % TICKER_COLORS.length],
  }))

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <Header mode={mode} onChange={setMode} />
      <div className="mt-2 flex flex-col gap-4 lg:flex-row lg:items-center">
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
                content={({ active, payload }) => {
                  if (!active || !payload || payload.length === 0) return null
                  const slice = (payload[0]?.payload ?? {}) as Slice
                  const pct = formatPercent(String(slice.weight ?? ''))
                  const mv = formatMoney(slice.marketValue, currency)
                  return (
                    <div className="rounded border border-slate-200 bg-white px-2.5 py-1.5 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
                      <div className="font-medium">{slice.name}</div>
                      {mode === 'sector' && slice.tickerCount != null ? (
                        <div className="text-slate-500 dark:text-slate-400">
                          {slice.tickerCount}개 종목
                        </div>
                      ) : null}
                      <div className="text-slate-700 dark:text-slate-200">
                        {pct} · {mv}
                      </div>
                    </div>
                  )
                }}
              />
            </Treemap>
          </ResponsiveContainer>
        </div>
        <AllocationLegend slices={slices} mode={mode} />
      </div>
    </section>
  )
}

function Header({
  mode,
  onChange,
}: {
  mode: AllocationMode
  onChange: (next: AllocationMode) => void
}) {
  return (
    <div className="flex items-center justify-between gap-2">
      <h3 className="text-sm font-medium text-slate-700 dark:text-slate-200">자산 비중</h3>
      <div
        role="tablist"
        aria-label="비중 그룹화 방식"
        className="inline-flex rounded-md border border-slate-300 bg-white p-0.5 text-xs font-medium dark:border-slate-700 dark:bg-slate-900"
      >
        {(
          [
            { value: 'ticker' as AllocationMode, label: '종목별' },
            { value: 'sector' as AllocationMode, label: 'sector별' },
          ]
        ).map(({ value, label }) => {
          const active = mode === value
          return (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => onChange(value)}
              className={
                active
                  ? 'rounded-sm bg-slate-900 px-2.5 py-1 text-white dark:bg-slate-100 dark:text-slate-900'
                  : 'rounded-sm px-2.5 py-1 text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'
              }
            >
              {label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

function buildTickerSlices(data: PortfolioView, currency: 'USD' | 'KRW'): Slice[] {
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
          name: CASH_LABEL_TICKER,
          weight: cashWeight,
          marketValue: currency === 'USD' ? data.cashUsd : data.cashKrw,
        }
      : null

  return [...positionSlices, ...(cashSlice ? [cashSlice] : [])].sort(
    (a, b) => b.weight - a.weight,
  )
}

function buildSectorSlices(data: PortfolioView, currency: 'USD' | 'KRW'): Slice[] {
  // 시세 가용 종목만 sector 별 합산. 시세 실패 종목은 weight/marketValue 가 null 이라 합계에서 제외.
  const grouped = new Map<
    string,
    { weight: number; marketValueUsd: number; marketValueKrw: number; tickerCount: number }
  >()
  for (const p of data.positions) {
    if (p.weight == null || p.marketValueUsd == null || p.marketValueKrw == null) continue
    const w = Number(p.weight)
    if (!Number.isFinite(w) || w <= 0) continue
    const mvUsd = Number(p.marketValueUsd)
    const mvKrw = Number(p.marketValueKrw)
    const key =
      typeof p.sector === 'string' && p.sector.trim().length > 0
        ? p.sector
        : SECTOR_UNCLASSIFIED
    const acc = grouped.get(key) ?? {
      weight: 0,
      marketValueUsd: 0,
      marketValueKrw: 0,
      tickerCount: 0,
    }
    acc.weight += w
    acc.marketValueUsd += Number.isFinite(mvUsd) ? mvUsd : 0
    acc.marketValueKrw += Number.isFinite(mvKrw) ? mvKrw : 0
    acc.tickerCount += 1
    grouped.set(key, acc)
  }

  const sliceList: Slice[] = Array.from(grouped.entries()).map(([name, agg]) => ({
    name,
    weight: agg.weight,
    marketValue: currency === 'USD' ? String(agg.marketValueUsd) : String(agg.marketValueKrw),
    tickerCount: agg.tickerCount,
  }))

  // 현금은 별도 슬라이스 — sector 그룹과 같은 색상 hash 함수에 통과시키지만 종목 수는 의미가 없어 미부여.
  const cashWeight = Number(data.cashWeight)
  if (Number.isFinite(cashWeight) && cashWeight > 0) {
    sliceList.push({
      name: CASH_LABEL_SECTOR,
      weight: cashWeight,
      marketValue: currency === 'USD' ? data.cashUsd : data.cashKrw,
    })
  }

  return sliceList.sort((a, b) => b.weight - a.weight)
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
  const fill = color ?? '#2563eb'
  const tickerFontSize = pickTickerFontSize(width, height)
  const pctFontSize = pickPctFontSize(width, height)
  const showTicker = tickerFontSize > 0 && Boolean(name)
  const showPct = pctFontSize > 0 && weight != null
  const tickerY = showPct ? y + height / 2 - tickerFontSize / 2 : y + height / 2
  const pctY = y + height / 2 + pctFontSize / 2 + 2
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
      {showTicker ? (
        <text
          x={x + width / 2}
          y={tickerY}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#fff"
          fontSize={tickerFontSize}
          fontWeight={400}
          style={{ pointerEvents: 'none' }}
        >
          {name}
        </text>
      ) : null}
      {showPct ? (
        <text
          x={x + width / 2}
          y={pctY}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#fff"
          fontSize={pctFontSize}
          opacity={0.85}
          style={{ pointerEvents: 'none' }}
        >
          {formatPercent(String(weight))}
        </text>
      ) : null}
    </g>
  )
}

// 셀이 좁아질수록 단계적으로 폰트 축소. 0 이면 라벨 숨김.
function pickTickerFontSize(w: number, h: number): number {
  if (w >= 70 && h >= 24) return 12
  if (w >= 50 && h >= 20) return 11
  if (w >= 32 && h >= 16) return 9
  return 0
}

function pickPctFontSize(w: number, h: number): number {
  if (w >= 80 && h >= 44) return 10
  if (w >= 60 && h >= 36) return 9
  return 0
}

interface LegendProps {
  slices: Slice[]
  mode: AllocationMode
}

function AllocationLegend({ slices, mode }: LegendProps) {
  return (
    <ul className="flex flex-col gap-1.5 text-sm lg:w-1/3">
      {slices.map((s, idx) => {
        const color =
          mode === 'sector' ? sectorHsl(s.name) : TICKER_COLORS[idx % TICKER_COLORS.length]
        return (
          <li
            key={s.name}
            className="flex items-center gap-2 text-slate-700 dark:text-slate-200"
          >
            <span
              className="inline-block h-3 w-3 shrink-0 rounded-sm"
              style={{ backgroundColor: color }}
              aria-hidden
            />
            <span className="flex-1 truncate">{s.name}</span>
            <span className="tabular-nums text-slate-600 dark:text-slate-300">
              {formatPercent(String(s.weight))}
            </span>
          </li>
        )
      })}
    </ul>
  )
}
