import { usePeriodWithStorage } from '../snapshots/useSnapshotPeriod'
import type { UseSnapshotPeriodResult } from '../snapshots/useSnapshotPeriod'

const STORAGE_KEY = 'pnl-period'

// 손익 페이지의 기간 선택 상태. 스냅샷 페이지와 동일한 PeriodSelector 를 공유하므로
// 같은 hook 구조를 그대로 재사용한다 (key 만 다름).
export function usePnlPeriod(): UseSnapshotPeriodResult {
  return usePeriodWithStorage(STORAGE_KEY)
}
