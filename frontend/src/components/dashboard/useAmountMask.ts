import { useCallback, useEffect, useState } from 'react'

const STORAGE_KEY = 'dashboard-amount-masked'

function readInitial(): boolean {
  if (typeof window === 'undefined') return false
  return window.localStorage.getItem(STORAGE_KEY) === 'true'
}

/**
 * Dashboard 한정 — 합계 카드와 PositionsTable 의 평가액/평가손익 컬럼을 가릴지 여부.
 * 기본은 표시(false). 캡처 공유 같은 명시적 의도 시 토글로 켠다.
 *
 * 티커 popover, AllocationTreemap 툴팁, % 카드(IRR/단순 수익률), 당일/수익률/52주 위치는
 * 마스킹 대상이 아니다.
 */
export function useAmountMask(): {
  masked: boolean
  toggle: () => void
} {
  const [masked, setMasked] = useState<boolean>(readInitial)

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, masked ? 'true' : 'false')
  }, [masked])

  const toggle = useCallback(() => setMasked((prev) => !prev), [])

  return { masked, toggle }
}
