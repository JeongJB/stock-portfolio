import { useQuery } from '@tanstack/react-query'
import { getPortfolio } from '../../api/client'
import type { PositionView } from '../../api/types'

// Dashboard 와 동일 queryKey 로 캐시 공유 — 추가 fetch 없이 보유 종목만 필요한 곳에서 재사용한다.
export function useOwnedPositions(): {
  positions: PositionView[]
  isLoading: boolean
} {
  const { data, isPending } = useQuery({
    queryKey: ['portfolio'],
    queryFn: getPortfolio,
  })
  return {
    positions: data?.positions ?? [],
    isLoading: isPending,
  }
}
