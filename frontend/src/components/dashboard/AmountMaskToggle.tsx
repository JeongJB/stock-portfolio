interface Props {
  masked: boolean
  onToggle: () => void
}

/**
 * 금액 마스킹 토글 — Dashboard 우상단 segmented control.
 * CurrencyToggle 과 동일한 시각 패턴 (둥근 테두리·padding·font-weight·버튼 사이즈).
 */
export function AmountMaskToggle({ masked, onToggle }: Props) {
  return (
    <div
      role="group"
      aria-label="금액 표시 여부"
      className="inline-flex rounded-md border border-slate-300 bg-white p-0.5 text-xs font-medium dark:border-slate-700 dark:bg-slate-900"
    >
      <button
        type="button"
        onClick={() => {
          if (masked) onToggle()
        }}
        aria-pressed={!masked}
        className={
          !masked
            ? 'min-h-[36px] rounded-sm bg-slate-900 px-3 py-1 text-white dark:bg-slate-100 dark:text-slate-900'
            : 'min-h-[36px] rounded-sm px-3 py-1 text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'
        }
      >
        표시
      </button>
      <button
        type="button"
        onClick={() => {
          if (!masked) onToggle()
        }}
        aria-pressed={masked}
        className={
          masked
            ? 'min-h-[36px] rounded-sm bg-slate-900 px-3 py-1 text-white dark:bg-slate-100 dark:text-slate-900'
            : 'min-h-[36px] rounded-sm px-3 py-1 text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'
        }
      >
        숨김
      </button>
    </div>
  )
}
