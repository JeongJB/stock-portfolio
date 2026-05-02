import { formatMoney } from '../../app/format'

interface Props {
  low: string | null
  high: string | null
  current: string | null
  ratio: string | null
}

/**
 * 52주 위치 시각화 — 가로 트랙 + 채움 영역 + 현재 위치 dot.
 * 호버 의존이 아니라 셀에 항상 시각적으로 노출. 가격 라벨은 USD 만 표기 (환율 무관, 표시 보조정보).
 *
 * ratio 가 null 이면 회색 dash 1개만 렌더 (52주 고저 동일가, 한쪽만 누락 등).
 */
export function WeekRangeBar({ low, high, current, ratio }: Props) {
  if (ratio == null || low == null || high == null) {
    return <span className="text-slate-500 dark:text-slate-400">—</span>
  }
  const pct = Math.max(0, Math.min(1, Number(ratio))) * 100
  const tooltip = current != null
    ? `저점 ${formatMoney(low, 'USD')} · 현재 ${formatMoney(current, 'USD')} · 고점 ${formatMoney(high, 'USD')}`
    : `저점 ${formatMoney(low, 'USD')} · 고점 ${formatMoney(high, 'USD')}`
  return (
    <div className="flex flex-col gap-1" title={tooltip}>
      <div className="relative h-2 w-full rounded bg-slate-200 dark:bg-slate-700">
        <div
          className="h-2 rounded bg-emerald-500"
          style={{ width: `${pct}%` }}
        />
        <div
          className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-slate-900 dark:bg-slate-100"
          style={{ left: `${pct}%` }}
        />
      </div>
      <div className="flex justify-between text-[10px] text-slate-500 dark:text-slate-400">
        <span>{formatMoney(low, 'USD')}</span>
        <span>{formatMoney(high, 'USD')}</span>
      </div>
    </div>
  )
}
