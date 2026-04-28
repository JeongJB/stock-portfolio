import { createContext, useContext } from 'react'
import type { Currency } from '../api/types'

export interface CurrencyContextValue {
  currency: Currency
  setCurrency: (c: Currency) => void
  toggle: () => void
}

export const CurrencyContext = createContext<CurrencyContextValue | null>(null)

export function useCurrency(): CurrencyContextValue {
  const ctx = useContext(CurrencyContext)
  if (!ctx) throw new Error('useCurrency must be used within CurrencyProvider')
  return ctx
}
