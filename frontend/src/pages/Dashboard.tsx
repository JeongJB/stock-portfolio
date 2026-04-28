import { useQuery } from '@tanstack/react-query'
import { getPortfolio } from '../api/client'
import { SummaryCards } from '../components/dashboard/SummaryCards'
import { AllocationPie } from '../components/dashboard/AllocationPie'
import { PositionsTable } from '../components/dashboard/PositionsTable'

export function Dashboard() {
  const query = useQuery({
    queryKey: ['portfolio'],
    queryFn: getPortfolio,
  })

  if (query.isPending) {
    return <DashboardSkeleton />
  }

  if (query.isError) {
    return (
      <section className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
        <p className="mb-2">
          포트폴리오를 불러오지 못했습니다: {(query.error as Error).message}
        </p>
        <button
          type="button"
          onClick={() => query.refetch()}
          className="rounded border border-rose-300 px-3 py-1 text-xs hover:bg-rose-100 dark:border-rose-800 dark:hover:bg-rose-900/40"
        >
          재시도
        </button>
      </section>
    )
  }

  const data = query.data

  return (
    <div className="space-y-4">
      <SummaryCards data={data} />
      <AllocationPie data={data} />
      <PositionsTable positions={data.positions} />
    </div>
  )
}

function DashboardSkeleton() {
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            className="h-24 animate-pulse rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800"
          />
        ))}
      </div>
      <div className="h-72 animate-pulse rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800" />
      <div className="h-48 animate-pulse rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800" />
      <p className="text-xs text-slate-500 dark:text-slate-400">로딩 중...</p>
    </div>
  )
}
