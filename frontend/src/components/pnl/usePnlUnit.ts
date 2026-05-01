import { useCallback, useEffect, useState } from 'react'

export type PnlUnit = 'monthly' | 'yearly'

const STORAGE_KEY = 'pnl-unit'
const DEFAULT_UNIT: PnlUnit = 'monthly'

function readInitial(): PnlUnit {
  if (typeof window === 'undefined') return DEFAULT_UNIT
  const raw = window.localStorage.getItem(STORAGE_KEY)
  return raw === 'monthly' || raw === 'yearly' ? raw : DEFAULT_UNIT
}

export interface UsePnlUnitResult {
  unit: PnlUnit
  setUnit: (u: PnlUnit) => void
}

export function usePnlUnit(): UsePnlUnitResult {
  const [unit, setUnitState] = useState<PnlUnit>(readInitial)

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, unit)
  }, [unit])

  const setUnit = useCallback((u: PnlUnit) => {
    setUnitState(u)
  }, [])

  return { unit, setUnit }
}
