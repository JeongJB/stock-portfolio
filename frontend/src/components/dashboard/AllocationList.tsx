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
  weight: number
  children: ListLeaf[]
}

const SECTOR_UNCLASSIFIED = '분류 미지정'
const CASH_SECTOR = '현금'
const CASH_TICKER = 'USD 현금'

/**
 * sector 그룹화 + 정렬된 비중 리스트. Treemap 옆에 정확한 숫자 보조 정보용.
 * weight 는 0~1. 막대 너비 = weight * 100 (전체 영역 대비 비중 — 종목 간 직접 비교 가능).
 */
export function AllocationList({ data, includeCash }: Props) {
  const { currency } = useCurrency()
  const branches = buildBranches(data, currency, includeCash)

  if (branches.length === 0) {
    return null
  }

  return (
    <ul className="space-y-3 text-xs">
      {branches.map((branch) => (
        <li key={branch.sector}>
          <div className="flex items-center justify-between text-sm font-medium text-slate-700 dark:text-slate-200">
            <span>{branch.sector}</span>
            <span className="font-mono tabular-nums">{formatPct(branch.weight)}</span>
          </div>
          <ul className="mt-1 space-y-1 pl-1">
            {branch.children.map((leaf) => (
              <li
                key={leaf.ticker}
                className="flex items-center gap-2 text-slate-600 dark:text-slate-300"
                title={`${leaf.ticker} · ${formatPct(leaf.weight)} · ${formatMoney(leaf.marketValue, currency)}`}
              >
                <span className="w-14 shrink-0 truncate font-mono">{leaf.ticker}</span>
                <div className="relative h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
                  <div
                    className={
                      leaf.isCash
                        ? 'absolute inset-y-0 left-0 rounded-full bg-slate-400 dark:bg-slate-500'
                        : 'absolute inset-y-0 left-0 rounded-full bg-slate-500 dark:bg-slate-400'
                    }
                    style={{ width: `${Math.min(100, leaf.weight * 100)}%` }}
                  />
                </div>
                <span className="w-12 shrink-0 text-right font-mono tabular-nums">
                  {formatPct(leaf.weight)}
                </span>
              </li>
            ))}
          </ul>
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
      weight: children.reduce((s, l) => s + l.weight, 0),
      // sector 안에서도 비중 큰 종목 먼저.
      children: children.slice().sort((a, b) => b.weight - a.weight),
    }))
    .sort((a, b) => b.weight - a.weight)

  // 현금 포함 모드 시에만 "현금" sector 추가 (가장 아래에 고정).
  if (includeCash) {
    const cashWeight = Number(data.cashWeight)
    if (Number.isFinite(cashWeight) && cashWeight > 0) {
      branches.push({
        sector: CASH_SECTOR,
        weight: cashWeight,
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

function formatPct(weight: number): string {
  return `${(weight * 100).toFixed(2)}%`
}
