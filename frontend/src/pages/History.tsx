import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteTrade, listTrades } from '../api/client'
import type { TradeView } from '../api/types'
import { useToast } from '../app/toastContext'
import { DeleteTradeModal } from '../components/history/DeleteTradeModal'
import { TradeHistoryTable } from '../components/history/TradeHistoryTable'

const HISTORY_LIMIT = 200

export function History() {
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const [target, setTarget] = useState<TradeView | null>(null)

  const query = useQuery({
    queryKey: ['trades', HISTORY_LIMIT],
    queryFn: () => listTrades(HISTORY_LIMIT),
  })

  const deleteMutation = useMutation({
    mutationFn: deleteTrade,
    onSuccess: () => {
      // 거래 / 포트폴리오 / 스냅샷 모두 영향 받음 — 일괄 invalidate.
      void queryClient.invalidateQueries({ queryKey: ['trades'] })
      void queryClient.invalidateQueries({ queryKey: ['portfolio'] })
      void queryClient.invalidateQueries({ queryKey: ['snapshots'] })
      showToast('거래가 삭제되었습니다', 'success')
      setTarget(null)
    },
    onError: (e: Error) => {
      // 422/404 메시지가 그대로 흘러와 사용자가 무엇이 잘못됐는지 식별 가능.
      showToast(`삭제 실패: ${e.message}`, 'error')
    },
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

      {query.isSuccess && (
        <TradeHistoryTable
          trades={query.data}
          onDelete={(trade) => setTarget(trade)}
          pendingDeleteId={deleteMutation.isPending ? target?.tradeId ?? null : null}
        />
      )}

      {target && (
        <DeleteTradeModal
          trade={target}
          isPending={deleteMutation.isPending}
          onCancel={() => {
            if (!deleteMutation.isPending) setTarget(null)
          }}
          onConfirm={() => deleteMutation.mutate(target.tradeId)}
        />
      )}
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
