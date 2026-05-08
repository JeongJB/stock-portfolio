interface Props {
  masked: boolean
  onToggle: () => void
}

/**
 * 금액 마스킹 토글 — Dashboard 우상단 segmented control.
 * USD/KRW 토글과 동일한 시각 일관성 유지 (작은 segmented control).
 */
export function AmountMaskToggle({ masked, onToggle }: Props) {
  return (
    <div
      className="inline-flex items-center rounded border border-slate-300 bg-white text-xs dark:border-slate-700 dark:bg-slate-900"
      role="group"
      aria-label="금액 표시 여부"
    >
      <button
        type="button"
        onClick={() => {
          if (masked) onToggle()
        }}
        aria-pressed={!masked}
        className={`min-h-[44px] px-3 ${
          !masked
            ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
            : 'text-slate-600 dark:text-slate-300'
        }`}
      >
        표시
      </button>
      <button
        type="button"
        onClick={() => {
          if (!masked) onToggle()
        }}
        aria-pressed={masked}
        className={`min-h-[44px] px-3 ${
          masked
            ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
            : 'text-slate-600 dark:text-slate-300'
        }`}
      >
        숨김
      </button>
    </div>
  )
}
