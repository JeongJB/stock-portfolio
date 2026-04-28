import { useCallback, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import type { Currency } from '../api/types'
import { CurrencyContext } from './currencyContext'

const STORAGE_KEY = 'display-currency'
const DEFAULT_CURRENCY: Currency = 'KRW'

function readInitial(): Currency {
  if (typeof window === 'undefined') return DEFAULT_CURRENCY
  const stored = window.localStorage.getItem(STORAGE_KEY)
  return stored === 'USD' || stored === 'KRW' ? stored : DEFAULT_CURRENCY
}

export function CurrencyProvider({ children }: { children: ReactNode }) {
  const [currency, setCurrencyState] = useState<Currency>(readInitial)

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, currency)
  }, [currency])

  const setCurrency = useCallback((c: Currency) => setCurrencyState(c), [])
  const toggle = useCallback(
    () => setCurrencyState((prev) => (prev === 'USD' ? 'KRW' : 'USD')),
    [],
  )

  return (
    <CurrencyContext.Provider value={{ currency, setCurrency, toggle }}>
      {children}
    </CurrencyContext.Provider>
  )
}
