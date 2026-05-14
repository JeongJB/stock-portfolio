import { useQuery } from '@tanstack/react-query'
import { getPortfolio } from '../../api/client'
import type { PositionView } from '../../api/types'

// Dashboard 와 동일 queryKey 로 캐시 공유 — 추가 fetch 없이 보유 종목만 필요한 곳에서 재사용한다.
// totalMarketValueUsd 는 TradeForm 의 비중 영향 미리보기에서 분모로 쓴다.
export function useOwnedPositions(): {
  positions: PositionView[]
  totalMarketValueUsd: number
  isLoading: boolean
} {
  const { data, isPending } = useQuery({
    queryKey: ['portfolio'],
    queryFn: getPortfolio,
  })
  return {
    positions: data?.positions ?? [],
    totalMarketValueUsd: data ? Number(data.totalMarketValueUsd) : 0,
    isLoading: isPending,
  }
}
