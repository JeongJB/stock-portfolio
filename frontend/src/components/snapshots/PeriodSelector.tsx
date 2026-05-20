import { useEffect, useState } from 'react'
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

// 'yyyy-MM-dd' → 'yyyyMMdd' (빈 문자열은 그대로).
function toCompact(iso: string): string {
  return iso.replace(/-/g, '')
}

// 'yyyyMMdd' (8자리) 가 실제 달력상 유효한 날짜면 'yyyy-MM-dd' 반환, 아니면 null.
function parseCompact(compact: string): string | null {
  if (!/^\d{8}$/.test(compact)) return null
  const year = Number(compact.slice(0, 4))
  const month = Number(compact.slice(4, 6))
  const day = Number(compact.slice(6, 8))
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  // 윤년·각 달 일수 검증.
  const d = new Date(Date.UTC(year, month - 1, day))
  if (
    d.getUTCFullYear() !== year ||
    d.getUTCMonth() !== month - 1 ||
    d.getUTCDate() !== day
  ) {
    return null
  }
  return `${compact.slice(0, 4)}-${compact.slice(4, 6)}-${compact.slice(6, 8)}`
}

interface CompactDateInputProps {
  value: string // 부모 상태(yyyy-MM-dd 또는 '')
  onChange: (iso: string) => void
  invalid: boolean
}

function CompactDateInput({ value, onChange, invalid }: CompactDateInputProps) {
  // 입력 도중 4~7자리 등 미완성 상태를 보존하기 위한 로컬 버퍼.
  const [buffer, setBuffer] = useState<string>(toCompact(value))

  // 프리셋 전환 등으로 부모 값이 외부에서 바뀌면 버퍼를 동기화.
  useEffect(() => {
    const compactFromProp = toCompact(value)
    if (parseCompact(buffer) === value) return // 이미 유효 동기 상태
    if (buffer === compactFromProp) return
    setBuffer(compactFromProp)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])

  const incomplete = buffer.length > 0 && buffer.length < 8
  const invalidCompleted = buffer.length === 8 && parseCompact(buffer) === null
  const showError = invalid || incomplete || invalidCompleted

  const inputBaseClass =
    'w-32 rounded-md border bg-white px-2 py-1 text-sm tabular-nums dark:bg-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500'
  const inputClass = showError
    ? `${inputBaseClass} border-rose-500 dark:border-rose-500`
    : `${inputBaseClass} border-slate-300 dark:border-slate-600`

  const handleChange = (raw: string) => {
    const digits = raw.replace(/\D/g, '').slice(0, 8)
    setBuffer(digits)
    if (digits.length === 0) {
      if (value !== '') onChange('')
      return
    }
    const iso = parseCompact(digits)
    if (iso) {
      if (iso !== value) onChange(iso)
    } else {
      // 미완성/잘못된 날짜 → 부모 상태는 '' 로 두어 range 가 invalid 임을 표현.
      if (value !== '') onChange('')
    }
  }

  return (
    <input
      type="text"
      inputMode="numeric"
      pattern="[0-9]*"
      maxLength={8}
      placeholder="YYYYMMDD"
      value={buffer}
      onChange={(e) => handleChange(e.target.value)}
      className={inputClass}
      aria-invalid={showError || undefined}
    />
  )
}

export function PeriodSelector({
  state,
  onPresetChange,
  onCustomFromChange,
  onCustomToChange,
}: PeriodSelectorProps) {
  const isCustom = state.preset === 'CUSTOM'
  const invalidRange =
    isCustom &&
    state.customFrom !== '' &&
    state.customTo !== '' &&
    state.customFrom > state.customTo

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
            시작 (YYYYMMDD)
            <CompactDateInput
              value={state.customFrom}
              onChange={onCustomFromChange}
              invalid={invalidRange}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-slate-600 dark:text-slate-300">
            종료 (YYYYMMDD)
            <CompactDateInput
              value={state.customTo}
              onChange={onCustomToChange}
              invalid={invalidRange}
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
