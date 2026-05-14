import type { PortfolioView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import { formatMoney } from '../../app/format'

interface Props {
  data: PortfolioView
  includeCash: boolean
}

interface ListLeaf {
  ticker: string
  weight: number
  marketValue: string | null
  isCash: boolean
}

interface ListBranch {
  sector: string
  children: ListLeaf[]
}

const SECTOR_UNCLASSIFIED = '분류 미지정'
const CASH_SECTOR = '현금'
const CASH_TICKER = 'USD 현금'

/**
 * sector 그룹화 + 비중 정렬된 종목 pill grid.
 * 각 종목 = pill (rounded chip). pill 배경 색 강도 = 종목 비중 / 전체 최대 비중 (5단계).
 * 비중 큰 종목이 시각적으로 두드러진다. 등락률·평가액은 PositionsTable 에 위임 — 여기엔 ticker + 비중만.
 */
export function AllocationList({ data, includeCash }: Props) {
  const { currency } = useCurrency()
  const branches = buildBranches(data, currency, includeCash)

  if (branches.length === 0) {
    return null
  }

  const maxWeight = Math.max(
    ...branches.flatMap((b) => b.children.map((l) => l.weight)),
    0,
  )

  return (
    <ul className="space-y-3">
      {branches.map((branch) => (
        <li key={branch.sector}>
          <div className="mb-1.5 text-sm font-medium text-slate-700 dark:text-slate-200">
            {branch.sector}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {branch.children.map((leaf) => {
              const intensity = maxWeight > 0 ? leaf.weight / maxWeight : 0
              return (
                <span
                  key={leaf.ticker}
                  className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs ${pillClasses(
                    intensity,
                  )}`}
                  title={`${leaf.ticker} · ${formatPct(leaf.weight)} · ${formatMoney(
                    leaf.marketValue,
                    currency,
                  )}`}
                >
                  <span className="font-mono font-semibold">{leaf.ticker}</span>
                  <span className="tabular-nums opacity-90">{formatPct(leaf.weight)}</span>
                </span>
              )
            })}
          </div>
        </li>
      ))}
    </ul>
  )
}

function buildBranches(
  data: PortfolioView,
  currency: 'USD' | 'KRW',
  includeCash: boolean,
): ListBranch[] {
  const grouped = new Map<string, ListLeaf[]>()
  for (const p of data.positions) {
    if (p.weight == null) continue
    const w = Number(p.weight)
    if (!Number.isFinite(w) || w <= 0) continue
    const sector =
      typeof p.sector === 'string' && p.sector.trim().length > 0
        ? p.sector
        : SECTOR_UNCLASSIFIED
    const leaves = grouped.get(sector) ?? []
    leaves.push({
      ticker: p.ticker,
      weight: w,
      marketValue: currency === 'USD' ? p.marketValueUsd : p.marketValueKrw,
      isCash: false,
    })
    grouped.set(sector, leaves)
  }

  // 현금 제외 모드: 종목 weight 합으로 재정규화 — Treemap 의 buildTree 와 동일 규칙.
  if (!includeCash) {
    const totalStockWeight = Array.from(grouped.values()).reduce(
      (sum, leaves) => sum + leaves.reduce((s, l) => s + l.weight, 0),
      0,
    )
    if (totalStockWeight > 0) {
      for (const leaves of grouped.values()) {
        for (const leaf of leaves) {
          leaf.weight = leaf.weight / totalStockWeight
        }
      }
    }
  }

  const branches: ListBranch[] = Array.from(grouped.entries())
    .map(([sector, children]) => ({
      sector,
      // sector 안에서도 비중 큰 종목 먼저.
      children: children.slice().sort((a, b) => b.weight - a.weight),
    }))
    .sort((a, b) => sumWeight(b.children) - sumWeight(a.children))

  // 현금 포함 모드 시에만 "현금" sector 추가 (가장 아래에 고정).
  if (includeCash) {
    const cashWeight = Number(data.cashWeight)
    if (Number.isFinite(cashWeight) && cashWeight > 0) {
      branches.push({
        sector: CASH_SECTOR,
        children: [
          {
            ticker: CASH_TICKER,
            weight: cashWeight,
            marketValue: currency === 'USD' ? data.cashUsd : data.cashKrw,
            isCash: true,
          },
        ],
      })
    }
  }

  return branches
}

function sumWeight(leaves: ListLeaf[]): number {
  return leaves.reduce((s, l) => s + l.weight, 0)
}

/**
 * 비중 강도(0~1) → pill Tailwind 클래스. 5단계 — 비중 큰 종목이 진하게.
 * 다크 모드는 contrast 위해 반전 (어두운 배경에선 진한 종목 = 밝은 pill).
 */
function pillClasses(intensity: number): string {
  if (intensity >= 0.8) {
    return 'bg-slate-800 text-slate-50 dark:bg-slate-100 dark:text-slate-900'
  }
  if (intensity >= 0.6) {
    return 'bg-slate-600 text-slate-50 dark:bg-slate-300 dark:text-slate-900'
  }
  if (intensity >= 0.4) {
    return 'bg-slate-400 text-slate-50 dark:bg-slate-500 dark:text-slate-50'
  }
  if (intensity >= 0.2) {
    return 'bg-slate-300 text-slate-700 dark:bg-slate-600 dark:text-slate-100'
  }
  return 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'
}

function formatPct(weight: number): string {
  return `${(weight * 100).toFixed(2)}%`
}
