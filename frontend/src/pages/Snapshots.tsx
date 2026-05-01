import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listSnapshots } from '../api/client'
import type { SnapshotView } from '../api/types'
import { PeriodSelector } from '../components/snapshots/PeriodSelector'
import { SnapshotTrendChart } from '../components/snapshots/SnapshotTrendChart'
import { TakeSnapshotButton } from '../components/snapshots/TakeSnapshotButton'
import { useSnapshotPeriod } from '../components/snapshots/useSnapshotPeriod'

const EMPTY_SNAPSHOTS: SnapshotView[] = []

export function Snapshots() {
  const { state, setPreset, setCustomFrom, setCustomTo, range } = useSnapshotPeriod()

  const query = useQuery({
    queryKey: ['snapshots', range.from, range.to],
    queryFn: () => listSnapshots(range.from, range.to),
  })

  // 박제 버튼이 응답 date 와 비교해 토스트 분기에 사용한다. fallback 빈 배열은
  // useMemo 안에서 처리해 react-query 캐시 동일 참조를 그대로 의존성으로 사용.
  const snapshotsData = query.data?.snapshots
  const snapshots = snapshotsData ?? EMPTY_SNAPSHOTS
  const existingDates = useMemo(
    () => new Set((snapshotsData ?? []).map((s) => s.date)),
    [snapshotsData],
  )

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 className="text-xl font-medium">스냅샷 추이</h2>
          <p className="text-sm text-slate-600 dark:text-slate-300">
            지금 시점의 총평가액·원금·환율을 박제하여 시계열로 비교합니다.
            같은 날짜에 여러 번 박제하면 마지막 값으로 갱신됩니다.
          </p>
        </div>
        <TakeSnapshotButton existingDates={existingDates} />
      </div>

      <PeriodSelector
        state={state}
        onPresetChange={setPreset}
        onCustomFromChange={setCustomFrom}
        onCustomToChange={setCustomTo}
      />

      {query.isPending && <SnapshotsSkeleton />}

      {query.isError && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
          <p className="mb-2">
            스냅샷 목록을 불러오지 못했습니다: {(query.error as Error).message}
          </p>
          <button
            type="button"
            onClick={() => query.refetch()}
            className="rounded border border-rose-300 px-3 py-1 text-xs hover:bg-rose-100 dark:border-rose-800 dark:hover:bg-rose-900/40"
          >
            재시도
          </button>
        </div>
      )}

      {query.isSuccess && snapshots.length === 0 && <SnapshotsEmptyState />}

      {query.isSuccess && snapshots.length > 0 && (
        <SnapshotTrendChart snapshots={snapshots} />
      )}
    </section>
  )
}

function SnapshotsSkeleton() {
  return (
    <div className="space-y-2">
      <div className="h-80 animate-pulse rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800" />
      <p className="text-xs text-slate-500 dark:text-slate-400">로딩 중...</p>
    </div>
  )
}

// 전체 0건과 기간 내 0건을 GET /api/snapshots?from&to 응답만으로는 구분할 수 없어
// 단일 메시지 + 기간 변경 안내로 단순화한다.
function SnapshotsEmptyState() {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/40 dark:text-slate-300">
      <p>선택한 기간에 박제된 스냅샷이 없습니다.</p>
      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
        위의 &lsquo;지금 박제&rsquo; 버튼으로 첫 스냅샷을 만들거나, 기간을 변경해 보세요.
      </p>
    </div>
  )
}
