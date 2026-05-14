import { useEffect, useState } from 'react'
import type { PortfolioView } from '../../api/types'
import { AllocationTreemap } from './AllocationTreemap'
import { AllocationList } from './AllocationList'

interface Props {
  data: PortfolioView
}

// 사용자 선호 (현금 포함/제외) localStorage 박제. 다음 방문 시 같은 모드.
const ALLOCATION_INCLUDE_CASH_KEY = 'allocation-include-cash'

function loadInitialIncludeCash(): boolean {
  if (typeof window === 'undefined') return true
  try {
    const v = window.localStorage.getItem(ALLOCATION_INCLUDE_CASH_KEY)
    return v === 'false' ? false : true
  } catch {
    return true
  }
}

export function AllocationPanel({ data }: Props) {
  const [includeCash, setIncludeCash] = useState<boolean>(loadInitialIncludeCash)

  useEffect(() => {
    try {
      window.localStorage.setItem(ALLOCATION_INCLUDE_CASH_KEY, String(includeCash))
    } catch {
      // localStorage 비활성화 환경 — 무시.
    }
  }, [includeCash])

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-slate-700 dark:text-slate-200">자산 비중</h3>
        <CashToggle value={includeCash} onChange={setIncludeCash} />
      </div>
      <div className="mt-3 flex flex-col gap-4 lg:flex-row lg:items-start">
        <div className="lg:flex-1">
          <AllocationTreemap data={data} includeCash={includeCash} />
        </div>
        <div className="w-full lg:w-72">
          <AllocationList data={data} includeCash={includeCash} />
        </div>
      </div>
    </section>
  )
}

function CashToggle({
  value,
  onChange,
}: {
  value: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <div
      role="tablist"
      aria-label="현금 포함 여부"
      className="inline-flex rounded-md border border-slate-300 bg-white p-0.5 text-xs font-medium dark:border-slate-700 dark:bg-slate-900"
    >
      {[
        { v: true, label: '현금 포함' },
        { v: false, label: '현금 제외' },
      ].map(({ v, label }) => {
        const active = value === v
        return (
          <button
            key={String(v)}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(v)}
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
  )
}
