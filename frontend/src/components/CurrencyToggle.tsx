import { useCurrency } from '../app/currencyContext'
import type { Currency } from '../api/types'

const OPTIONS: Currency[] = ['KRW', 'USD']

export function CurrencyToggle() {
  const { currency, setCurrency } = useCurrency()
  return (
    <div
      role="group"
      aria-label="표시 통화"
      className="inline-flex rounded-md border border-slate-300 bg-white p-0.5 text-xs font-medium dark:border-slate-700 dark:bg-slate-900"
    >
      {OPTIONS.map((opt) => {
        const active = currency === opt
        return (
          <button
            key={opt}
            type="button"
            aria-pressed={active}
            onClick={() => setCurrency(opt)}
            className={
              active
                ? 'rounded-sm bg-slate-900 px-3 py-1 text-white dark:bg-slate-100 dark:text-slate-900'
                : 'rounded-sm px-3 py-1 text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'
            }
          >
            {opt}
          </button>
        )
      })}
    </div>
  )
}
