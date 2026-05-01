import { useEffect, useRef } from 'react'
import type { TradeView } from '../../api/types'
import { formatKstDateTime, formatMoney, formatQty } from '../../app/format'

interface Props {
  trade: TradeView
  isPending: boolean
  onCancel: () => void
  onConfirm: () => void
}

const TYPE_LABEL: Record<TradeView['type'], string> = {
  BUY: '매수',
  SELL: '매도',
  DEPOSIT: '입금',
  WITHDRAW: '출금',
  DIVIDEND: '배당',
}

export function DeleteTradeModal({ trade, isPending, onCancel, onConfirm }: Props) {
  const confirmRef = useRef<HTMLButtonElement>(null)

  // ESC 닫기 + Enter 확정. 외부 클릭은 backdrop onClick 으로 닫는다.
  useEffect(() => {
    function handleKey(e: KeyboardEvent) {
      if (isPending) return
      if (e.key === 'Escape') {
        e.preventDefault()
        onCancel()
      } else if (e.key === 'Enter') {
        e.preventDefault()
        onConfirm()
      }
    }
    document.addEventListener('keydown', handleKey)
    confirmRef.current?.focus()
    return () => document.removeEventListener('keydown', handleKey)
  }, [isPending, onCancel, onConfirm])

  // 거래 요약: BUY/SELL 은 ticker + 수량 + 단가, 그 외는 금액.
  const summary = buildSummary(trade)

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-trade-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget && !isPending) onCancel()
      }}
    >
      <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-5 shadow-xl dark:border-slate-700 dark:bg-slate-900">
        <h2 id="delete-trade-title" className="text-lg font-semibold text-slate-900 dark:text-slate-100">
          거래 삭제 확인
        </h2>

        <dl className="mt-4 space-y-1 text-sm">
          <Row label="시각">{formatKstDateTime(trade.executedAt)}</Row>
          <Row label="종류">{TYPE_LABEL[trade.type]}</Row>
          {summary.map((row) => (
            <Row key={row.label} label={row.label}>{row.value}</Row>
          ))}
        </dl>

        <p className="mt-4 rounded-md border border-amber-300 bg-amber-50 p-3 text-xs text-amber-800 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
          이 거래를 삭제하면 이후의 잔고와 손익 계산이 모두 다시 계산됩니다.
          삭제 후 잔고가 음수가 되거나 보유 수량이 부족해지면 삭제가 거부됩니다.
        </p>

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={isPending}
            className="min-h-[44px] rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-600 dark:hover:bg-slate-800"
          >
            취소
          </button>
          <button
            ref={confirmRef}
            type="button"
            onClick={onConfirm}
            disabled={isPending}
            className="min-h-[44px] rounded-md bg-rose-600 px-4 py-2 text-sm font-medium text-white hover:bg-rose-700 disabled:cursor-not-allowed disabled:bg-rose-400"
          >
            {isPending ? '삭제 중...' : '삭제'}
          </button>
        </div>
      </div>
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-slate-500 dark:text-slate-400">{label}</dt>
      <dd className="text-right font-medium text-slate-800 dark:text-slate-100">{children}</dd>
    </div>
  )
}

interface SummaryRow { label: string; value: string }

function buildSummary(t: TradeView): SummaryRow[] {
  const rows: SummaryRow[] = []
  if (t.ticker) rows.push({ label: '종목', value: t.ticker })
  if (t.qty) rows.push({ label: '수량', value: formatQty(t.qty) })
  if (t.price) rows.push({ label: '단가', value: formatMoney(t.price, 'USD') })
  if (t.cashAmount) rows.push({ label: '금액', value: formatMoney(t.cashAmount, 'USD') })
  return rows
}
