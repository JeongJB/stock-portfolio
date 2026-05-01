import type { PeriodPreset, PeriodState } from './useSnapshotPeriod'

interface PeriodSelectorProps {
  state: PeriodState
  onPresetChange: (preset: PeriodPreset) => void
  onCustomFromChange: (date: string) => void
  onCustomToChange: (date: string) => void
}

const PRESET_OPTIONS: ReadonlyArray<{ value: PeriodPreset; label: string }> = [
  { value: '1M', label: '1달' },
  { value: '3M', label: '3달' },
  { value: '6M', label: '6달' },
  { value: '1Y', label: '1년' },
  { value: '5Y', label: '5년' },
  { value: 'CUSTOM', label: '사용자 지정' },
]

const ACTIVE_CLASS = 'bg-blue-600 text-white dark:bg-blue-500'
const INACTIVE_CLASS =
  'bg-slate-100 text-slate-700 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'

export function PeriodSelector({
  state,
  onPresetChange,
  onCustomFromChange,
  onCustomToChange,
}: PeriodSelectorProps) {
  const isCustom = state.preset === 'CUSTOM'
  const invalidRange = isCustom && state.customFrom !== '' && state.customTo !== '' && state.customFrom > state.customTo
  const inputBaseClass =
    'rounded-md border bg-white px-2 py-1 text-sm dark:bg-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500'
  const inputClass = invalidRange
    ? `${inputBaseClass} border-rose-500 dark:border-rose-500`
    : `${inputBaseClass} border-slate-300 dark:border-slate-600`

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2" role="group" aria-label="기간 선택">
        {PRESET_OPTIONS.map((opt) => {
          const active = state.preset === opt.value
          return (
            <button
              key={opt.value}
              type="button"
              onClick={() => onPresetChange(opt.value)}
              aria-pressed={active}
              className={`min-h-[44px] rounded-full px-3 text-sm font-medium transition-colors ${active ? ACTIVE_CLASS : INACTIVE_CLASS}`}
            >
              {opt.label}
            </button>
          )
        })}
      </div>

      {isCustom && (
        <div className="flex flex-wrap items-end gap-3 pt-1">
          <label className="flex flex-col gap-1 text-xs text-slate-600 dark:text-slate-300">
            시작
            <input
              type="date"
              value={state.customFrom}
              onChange={(e) => onCustomFromChange(e.target.value)}
              className={inputClass}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-slate-600 dark:text-slate-300">
            종료
            <input
              type="date"
              value={state.customTo}
              onChange={(e) => onCustomToChange(e.target.value)}
              className={inputClass}
            />
          </label>
          {invalidRange && (
            <p className="text-xs text-rose-600 dark:text-rose-400">
              시작일이 종료일보다 늦습니다.
            </p>
          )}
        </div>
      )}
    </div>
  )
}
