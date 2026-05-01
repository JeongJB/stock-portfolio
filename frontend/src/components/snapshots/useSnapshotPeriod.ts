import { useCallback, useEffect, useMemo, useState } from 'react'
import { formatKstDate, monthsAgoKst, yearsAgoKst } from '../../app/format'

export type PeriodPreset = '1M' | '3M' | '6M' | '1Y' | '5Y' | 'CUSTOM'

export interface PeriodState {
  preset: PeriodPreset
  customFrom: string // yyyy-MM-dd KST
  customTo: string // yyyy-MM-dd KST
}

export interface UseSnapshotPeriodResult {
  state: PeriodState
  setPreset: (preset: PeriodPreset) => void
  setCustomFrom: (date: string) => void
  setCustomTo: (date: string) => void
  range: { from: string; to: string }
}

const STORAGE_KEY = 'snapshot-period'
const VALID_PRESETS: readonly PeriodPreset[] = ['1M', '3M', '6M', '1Y', '5Y', 'CUSTOM']

function defaultState(): PeriodState {
  return {
    preset: '1M',
    customFrom: monthsAgoKst(6),
    customTo: formatKstDate(),
  }
}

function isValidIsoDate(s: unknown): s is string {
  return typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s)
}

function readInitial(): PeriodState {
  if (typeof window === 'undefined') return defaultState()
  const raw = window.localStorage.getItem(STORAGE_KEY)
  if (!raw) return defaultState()
  try {
    const parsed = JSON.parse(raw) as Partial<PeriodState>
    const fallback = defaultState()
    const preset =
      typeof parsed.preset === 'string' && VALID_PRESETS.includes(parsed.preset as PeriodPreset)
        ? (parsed.preset as PeriodPreset)
        : fallback.preset
    const customFrom = isValidIsoDate(parsed.customFrom) ? parsed.customFrom : fallback.customFrom
    const customTo = isValidIsoDate(parsed.customTo) ? parsed.customTo : fallback.customTo
    return { preset, customFrom, customTo }
  } catch {
    return defaultState()
  }
}

// preset 별 from 계산. 'CUSTOM' 은 호출 측에서 별도 분기.
function presetFrom(preset: Exclude<PeriodPreset, 'CUSTOM'>): string {
  switch (preset) {
    case '1M':
      return monthsAgoKst(1)
    case '3M':
      return monthsAgoKst(3)
    case '6M':
      return monthsAgoKst(6)
    case '1Y':
      return yearsAgoKst(1)
    case '5Y':
      return yearsAgoKst(5)
  }
}

export function useSnapshotPeriod(): UseSnapshotPeriodResult {
  const [state, setState] = useState<PeriodState>(readInitial)

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  }, [state])

  const setPreset = useCallback((preset: PeriodPreset) => {
    setState((prev) => ({ ...prev, preset }))
  }, [])

  const setCustomFrom = useCallback((date: string) => {
    setState((prev) => ({ ...prev, customFrom: date }))
  }, [])

  const setCustomTo = useCallback((date: string) => {
    setState((prev) => ({ ...prev, customTo: date }))
  }, [])

  const range = useMemo(() => {
    if (state.preset === 'CUSTOM') {
      return { from: state.customFrom, to: state.customTo }
    }
    return { from: presetFrom(state.preset), to: formatKstDate() }
  }, [state])

  return { state, setPreset, setCustomFrom, setCustomTo, range }
}
