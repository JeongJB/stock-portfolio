import { useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getPortfolio } from '../api/client'
import { SummaryCards } from '../components/dashboard/SummaryCards'
import { AllocationTreemap } from '../components/dashboard/AllocationTreemap'
import { PositionsTable } from '../components/dashboard/PositionsTable'
import { exportElementAsPng } from '../app/exportImage'
import { formatKstDate } from '../app/format'
import { useToast } from '../app/toastContext'

export function Dashboard() {
  const query = useQuery({
    queryKey: ['portfolio'],
    queryFn: getPortfolio,
  })

  const captureRef = useRef<HTMLDivElement>(null)
  const [exporting, setExporting] = useState(false)
  const { showToast } = useToast()

  const handleExport = async () => {
    if (!captureRef.current || exporting) return
    setExporting(true)
    try {
      const filename = `portfolio-${formatKstDate()}.png`
      await exportElementAsPng(captureRef.current, filename)
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      showToast(`이미지 저장 실패: ${message}`, 'error')
    } finally {
      setExporting(false)
    }
  }

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
    <div ref={captureRef} className="space-y-4">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={handleExport}
          disabled={exporting}
          className="inline-flex min-h-[44px] items-center gap-1.5 rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          <DownloadIcon />
          {exporting ? '저장 중...' : '이미지로 저장'}
        </button>
      </div>
      <SummaryCards data={data} />
      <AllocationTreemap data={data} />
      <PositionsTable positions={data.positions} />
    </div>
  )
}

function DownloadIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 20 20"
      fill="currentColor"
      className="h-3.5 w-3.5"
      aria-hidden="true"
    >
      <path d="M10.75 2.75a.75.75 0 0 0-1.5 0v8.69L6.03 8.22a.75.75 0 0 0-1.06 1.06l4.5 4.5a.75.75 0 0 0 1.06 0l4.5-4.5a.75.75 0 1 0-1.06-1.06l-3.22 3.22V2.75Z" />
      <path d="M3.5 13.75a.75.75 0 0 0-1.5 0v2.5A2.75 2.75 0 0 0 4.75 19h10.5A2.75 2.75 0 0 0 18 16.25v-2.5a.75.75 0 0 0-1.5 0v2.5c0 .69-.56 1.25-1.25 1.25H4.75c-.69 0-1.25-.56-1.25-1.25v-2.5Z" />
    </svg>
  )
}

function DashboardSkeleton() {
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5">
        {[0, 1, 2, 3, 4].map((i) => (
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
