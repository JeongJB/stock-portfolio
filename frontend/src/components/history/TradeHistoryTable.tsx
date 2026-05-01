import type { TradeView, TradeType } from '../../api/types'
import { formatKstDateTime, formatMoney, formatQty } from '../../app/format'

interface Props {
  trades: TradeView[]
}

// 거래 종류별 한국어 라벨 + 배지 색상. USD/KRW 토글과 무관 — 거래 시점 환율을 박제하지 않으므로
// 거래 내역은 USD 기준으로만 표시한다.
const TYPE_META: Record<TradeType, { label: string; badgeClass: string }> = {
  BUY: {
    label: '매수',
    badgeClass: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  },
  SELL: {
    label: '매도',
    badgeClass: 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-300',
  },
  DEPOSIT: {
    label: '입금',
    badgeClass: 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300',
  },
  WITHDRAW: {
    label: '출금',
    badgeClass: 'bg-slate-200 text-slate-700 dark:bg-slate-700/60 dark:text-slate-200',
  },
  DIVIDEND: {
    label: '배당',
    badgeClass: 'bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-300',
  },
}

export function TradeHistoryTable({ trades }: Props) {
  if (trades.length === 0) {
    return (
      <section className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/40 dark:text-slate-300">
        거래 내역이 없습니다.
      </section>
    )
  }

  return (
    <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-800 dark:text-slate-400">
          <tr>
            <Th align="left" className="sticky left-0 z-10 bg-slate-50 dark:bg-slate-800">시각 (KST)</Th>
            <Th align="left">종류</Th>
            <Th align="left">종목</Th>
            <Th>수량</Th>
            <Th>단가 (USD)</Th>
            <Th>금액 (USD)</Th>
            <Th align="left">비고</Th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {trades.map((t) => (
            <Row key={t.tradeId} trade={t} />
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Row({ trade }: { trade: TradeView }) {
  const meta = TYPE_META[trade.type]
  // 금액 컬럼: BUY/SELL 은 qty * price, DEPOSIT/WITHDRAW/DIVIDEND 는 cashAmount.
  const amountUsd = computeAmountUsd(trade)
  const showTicker = trade.type !== 'DEPOSIT' && trade.type !== 'WITHDRAW'
  const showQty = trade.type === 'BUY' || trade.type === 'SELL'
  const showPrice = trade.type === 'BUY' || trade.type === 'SELL'
  const showMemo = trade.type === 'DEPOSIT' || trade.type === 'WITHDRAW'

  return (
    <tr>
      <Td align="left" className="sticky left-0 z-10 bg-white text-slate-700 dark:bg-slate-900 dark:text-slate-200">
        {formatKstDateTime(trade.executedAt)}
      </Td>
      <Td align="left">
        <span
          className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${meta.badgeClass}`}
        >
          {meta.label}
        </span>
      </Td>
      <Td align="left" className="font-medium">
        {showTicker ? (trade.ticker ?? '—') : '—'}
      </Td>
      <Td>{showQty ? formatQty(trade.qty) : '—'}</Td>
      <Td>{showPrice ? formatMoney(trade.price, 'USD') : '—'}</Td>
      <Td>{amountUsd != null ? formatMoney(amountUsd, 'USD') : '—'}</Td>
      <Td align="left" wrap className="max-w-xs break-words text-slate-600 dark:text-slate-300">
        {showMemo ? (trade.memo ?? '—') : '—'}
      </Td>
    </tr>
  )
}

// BUY/SELL 의 응답에는 cashAmount 가 없으므로 qty * price 로 계산한다.
// formatMoney 가 받을 수 있도록 string 으로 반환.
function computeAmountUsd(trade: TradeView): string | null {
  if (trade.cashAmount) return trade.cashAmount
  if (trade.qty && trade.price) {
    const n = Number(trade.qty) * Number(trade.price)
    if (Number.isFinite(n)) return n.toString()
  }
  return null
}

function Th({
  children,
  align = 'right',
  className = '',
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
  className?: string
}) {
  return (
    <th
      scope="col"
      className={`whitespace-nowrap px-3 py-2 ${align === 'left' ? 'text-left' : 'text-right'} ${className}`}
    >
      {children}
    </th>
  )
}

function Td({
  children,
  align = 'right',
  className = '',
  wrap = false,
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
  className?: string
  // 비고 셀처럼 긴 텍스트는 wrap 허용. 기본은 nowrap 으로 가로 스크롤 시 컬럼 정렬 유지.
  wrap?: boolean
}) {
  return (
    <td
      className={`${wrap ? 'whitespace-pre-wrap' : 'whitespace-nowrap'} px-3 py-2 tabular-nums ${align === 'left' ? 'text-left' : 'text-right'} ${className}`}
    >
      {children}
    </td>
  )
}
