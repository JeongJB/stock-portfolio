import { useQuery } from '@tanstack/react-query'
import { listTrades } from '../api/client'
import { TradeHistoryTable } from '../components/history/TradeHistoryTable'

const HISTORY_LIMIT = 200

export function History() {
  const query = useQuery({
    queryKey: ['trades', HISTORY_LIMIT],
    queryFn: () => listTrades(HISTORY_LIMIT),
  })

  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h2 className="text-xl font-medium">거래 내역</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          최근 {HISTORY_LIMIT}건까지의 거래를 시각 역순으로 보여줍니다.
          금액은 USD 기준으로만 표시됩니다 (거래 시점 환율을 박제하지 않음).
        </p>
      </div>

      {query.isPending && <HistorySkeleton />}

      {query.isError && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
          <p className="mb-2">
            거래 내역을 불러오지 못했습니다: {(query.error as Error).message}
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

      {query.isSuccess && <TradeHistoryTable trades={query.data} />}
    </section>
  )
}

function HistorySkeleton() {
  return (
    <div className="space-y-2">
      <div className="h-64 animate-pulse rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800" />
      <p className="text-xs text-slate-500 dark:text-slate-400">로딩 중...</p>
    </div>
  )
}
