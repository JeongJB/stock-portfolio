import type { PnlUnit } from './usePnlUnit'

interface Props {
  unit: PnlUnit
  onChange: (u: PnlUnit) => void
}

const OPTIONS: ReadonlyArray<{ value: PnlUnit; label: string }> = [
  { value: 'monthly', label: '월별' },
  { value: 'yearly', label: '연별' },
]

const ACTIVE_CLASS = 'bg-blue-600 text-white dark:bg-blue-500'
const INACTIVE_CLASS =
  'bg-slate-100 text-slate-700 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'

export function PnlUnitToggle({ unit, onChange }: Props) {
  return (
    <div className="flex flex-wrap gap-2" role="group" aria-label="단위 선택">
      {OPTIONS.map((opt) => {
        const active = unit === opt.value
        return (
          <button
            key={opt.value}
            type="button"
            onClick={() => onChange(opt.value)}
            aria-pressed={active}
            className={`min-h-[44px] rounded-full px-3 text-sm font-medium transition-colors ${active ? ACTIVE_CLASS : INACTIVE_CLASS}`}
          >
            {opt.label}
          </button>
        )
      })}
    </div>
  )
}
