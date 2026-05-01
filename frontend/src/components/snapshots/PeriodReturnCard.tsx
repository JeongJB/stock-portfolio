import type { SnapshotView } from '../../api/types'
import { formatMoney, formatSignedPercent, pnlColorClass } from '../../app/format'

interface Props {
  snapshots: SnapshotView[]
}

/**
 * 선택한 기간 내 가장 오래된 스냅샷과 가장 최근 스냅샷의 총액(USD, 현금+평가액)을 비교한 단순 변화율.
 * 입금/출금 보정 없음 — 사용자가 명시적으로 단순 % change 만 표시하도록 결정.
 * 0건/1건이거나 firstValue == 0 이면 카드 자체를 표시하지 않는다 (호출 측에서 null 반환).
 */
export function PeriodReturnCard({ snapshots }: Props) {
  if (snapshots.length < 2) return null

  // 응답이 date ASC 순서라는 백엔드 계약 (PortfolioRepository.findSnapshots) 에 의존.
  const first = snapshots[0]
  const last = snapshots[snapshots.length - 1]
  const firstValue = Number(first.totalAssetsUsd)
  const lastValue = Number(last.totalAssetsUsd)
  if (!Number.isFinite(firstValue) || !Number.isFinite(lastValue)) return null
  if (firstValue === 0) return null

  const pct = (lastValue - firstValue) / firstValue
  const pctStr = String(pct)
  const colorClass = pnlColorClass(pctStr)

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <h3 className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">
        기간 총액 변화 (입금/출금 미보정)
      </h3>
      <div className="mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <p className={`text-2xl font-semibold tabular-nums ${colorClass}`}>
          {formatSignedPercent(pctStr)}
        </p>
        <p className="text-sm tabular-nums text-slate-600 dark:text-slate-300">
          {formatMoney(first.totalAssetsUsd, 'USD')} →{' '}
          {formatMoney(last.totalAssetsUsd, 'USD')}
        </p>
      </div>
      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
        {first.date} ~ {last.date}
      </p>
    </section>
  )
}
