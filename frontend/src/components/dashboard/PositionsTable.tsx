import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { PositionView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import {
  formatMoney,
  formatQty,
  formatSignedMoney,
  pnlColorClass,
} from '../../app/format'
import { WeekRangeBar } from './WeekRangeBar'

interface Props {
  positions: PositionView[]
}

export function PositionsTable({ positions }: Props) {
  const { currency } = useCurrency()
  const unit = currency === 'USD' ? 'USD' : 'KRW'

  // 평가액 내림차순. null 행은 맨 뒤(qty 보조 정렬).
  const sorted = [...positions].sort((a, b) => {
    const aMv = currency === 'USD' ? a.marketValueUsd : a.marketValueKrw
    const bMv = currency === 'USD' ? b.marketValueUsd : b.marketValueKrw
    if (aMv == null && bMv == null) return 0
    if (aMv == null) return 1
    if (bMv == null) return -1
    return Number(bMv) - Number(aMv)
  })

  if (sorted.length === 0) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
        보유 종목이 없습니다.
      </section>
    )
  }

  return (
    <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-800 dark:text-slate-400">
          <tr>
            <Th align="left">티커</Th>
            <Th>당일</Th>
            <Th>수익률</Th>
            <Th>평가액 ({unit})</Th>
            <Th>평가손익 ({unit})</Th>
            <Th>52주 위치</Th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {sorted.map((p) => (
            <Row key={p.ticker} position={p} currency={currency} />
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Row({
  position,
  currency,
}: {
  position: PositionView
  currency: 'USD' | 'KRW'
}) {
  const isUsd = currency === 'USD'
  const avgCost = isUsd ? position.avgCostUsd : position.avgCostKrw
  const lastPrice = isUsd ? position.lastPriceUsd : position.lastPriceKrw
  const marketValue = isUsd ? position.marketValueUsd : position.marketValueKrw
  const pnl = isUsd ? position.unrealizedPnlUsd : position.unrealizedPnlKrw
  const quoteFailed = position.lastPriceUsd == null
  const totalReturnPct = computeTotalReturnPct(
    position.lastPriceUsd,
    position.avgCostUsd,
  )

  const rowClass = quoteFailed
    ? 'bg-slate-100 dark:bg-slate-800/50 text-slate-500 dark:text-slate-400'
    : ''

  const [open, setOpen] = useState(false)
  const [hover, setHover] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const popoverRef = useRef<HTMLDivElement>(null)
  const closeTimer = useRef<number | null>(null)
  const isOpen = open || hover

  const cancelClose = () => {
    if (closeTimer.current != null) {
      clearTimeout(closeTimer.current)
      closeTimer.current = null
    }
  }
  // 버튼→팝오버 사이 4px gap 을 마우스가 건널 때 깜빡이지 않도록 close 를 살짝 지연.
  const scheduleClose = () => {
    cancelClose()
    closeTimer.current = window.setTimeout(() => {
      setHover(false)
      closeTimer.current = null
    }, 120)
  }
  useEffect(() => () => cancelClose(), [])

  useEffect(() => {
    if (!open) return
    const onMouseDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (buttonRef.current?.contains(t)) return
      if (popoverRef.current?.contains(t)) return
      setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onMouseDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onMouseDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  // 팝오버 마운트 시 1회 위치 측정. callback ref 는 commit 단계(브라우저 paint 직전)에
  // 동기 실행되므로 직접 style 을 써도 깜빡임 없음. state·effect 미사용 → 캐스케이드 렌더 회피.
  const setPopoverNode = useCallback((node: HTMLDivElement | null) => {
    popoverRef.current = node
    if (!node) return
    const button = buttonRef.current
    if (!button) return
    const rect = button.getBoundingClientRect()
    node.style.top = `${rect.bottom + window.scrollY + 4}px`
    node.style.left = `${rect.left + window.scrollX}px`
  }, [])

  return (
    <tr className={rowClass}>
      <Td align="left" className="font-medium">
        <button
          type="button"
          ref={buttonRef}
          className="rounded underline decoration-dotted underline-offset-4 hover:text-blue-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:hover:text-blue-400"
          onClick={() => setOpen((o) => !o)}
          onMouseEnter={() => {
            cancelClose()
            setHover(true)
          }}
          onMouseLeave={scheduleClose}
          aria-expanded={isOpen}
          aria-haspopup="dialog"
        >
          {position.ticker}
        </button>
        {quoteFailed && (
          <span
            className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-normal text-amber-800 dark:bg-amber-900/40 dark:text-amber-300"
            title="시세 조회 실패"
          >
            시세 없음
          </span>
        )}
        {isOpen
          ? createPortal(
              <div
                ref={setPopoverNode}
                role="dialog"
                className="absolute z-50 min-w-45 rounded-lg border border-slate-200 bg-white p-3 shadow-lg dark:border-slate-700 dark:bg-slate-800"
                onMouseEnter={() => {
                  cancelClose()
                  setHover(true)
                }}
                onMouseLeave={scheduleClose}
              >
                <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
                  <dt className="text-slate-500 dark:text-slate-400">수량</dt>
                  <dd className="text-right tabular-nums text-slate-700 dark:text-slate-200">
                    {formatQty(position.qty)}
                  </dd>
                  <dt className="text-slate-500 dark:text-slate-400">평단</dt>
                  <dd className="text-right tabular-nums text-slate-700 dark:text-slate-200">
                    {formatMoney(avgCost, currency)}
                  </dd>
                  <dt className="text-slate-500 dark:text-slate-400">현재가</dt>
                  <dd className="text-right tabular-nums text-slate-700 dark:text-slate-200">
                    {quoteFailed ? '—' : formatMoney(lastPrice, currency)}
                  </dd>
                </dl>
              </div>,
              document.body,
            )
          : null}
      </Td>
      <Td className={changePctColorClass(position.dailyChangePct)}>
        {formatChangePct(position.dailyChangePct)}
      </Td>
      <Td className={changePctColorClass(totalReturnPct)}>
        {formatChangePct(totalReturnPct)}
      </Td>
      <Td>{quoteFailed ? '—' : formatMoney(marketValue, currency)}</Td>
      <Td className={quoteFailed ? '' : pnlColorClass(pnl)}>
        {quoteFailed ? '—' : formatSignedMoney(pnl, currency)}
      </Td>
      <Td className="min-w-35">
        <WeekRangeBar
            low={position.weekLow52Usd}
            high={position.weekHigh52Usd}
            current={position.lastPriceUsd}
            ratio={position.weekRangeRatio}
        />
      </Td>
    </tr>
  )
}

/**
 * 평단 대비 총수익률(%) = (현재가 / 평단 - 1) * 100. USD 기준 계산(통화 무관 비율).
 * 시세 실패·평단 0 이하·NaN 은 null → '—' 로 표시.
 */
function computeTotalReturnPct(
  lastPriceUsd: string | null,
  avgCostUsd: string,
): string | null {
  if (lastPriceUsd == null) return null
  const last = Number(lastPriceUsd)
  const avg = Number(avgCostUsd)
  if (!Number.isFinite(last) || !Number.isFinite(avg) || avg <= 0) return null
  return String((last / avg - 1) * 100)
}

/**
 * 등락률 표시: "+1.23%" / "-4.56%" / "0.00%" / "—".
 * 이미 % 단위로 들어오므로 (예: "1.23" = 1.23%) Intl.NumberFormat percent 미적용.
 */
function formatChangePct(value: string | null | undefined): string {
  if (value == null || value === '') return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return '—'
  const sign = n > 0 ? '+' : n < 0 ? '-' : ''
  return `${sign}${Math.abs(n).toFixed(2)}%`
}

/**
 * 등락률 색상: |값| >= 0.005 (≈ ±0.01% 반올림 임계) → 초록/빨강, 그 외 회색.
 * 보합(0)·null·미세값은 정보 손실 없이 회색으로 보합 표시.
 */
function changePctColorClass(value: string | null | undefined): string {
  if (value == null || value === '') return 'text-slate-500 dark:text-slate-400'
  const n = Number(value)
  if (Number.isNaN(n)) return 'text-slate-500 dark:text-slate-400'
  if (n >= 0.005) return 'text-emerald-600 dark:text-emerald-400'
  if (n <= -0.005) return 'text-rose-600 dark:text-rose-400'
  return 'text-slate-500 dark:text-slate-400'
}

function Th({
  children,
  align = 'right',
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
}) {
  return (
    <th
      scope="col"
      className={`whitespace-nowrap px-3 py-2 ${align === 'left' ? 'text-left' : 'text-right'}`}
    >
      {children}
    </th>
  )
}

function Td({
  children,
  align = 'right',
  className = '',
}: {
  children: React.ReactNode
  align?: 'left' | 'right'
  className?: string
}) {
  return (
    <td
      className={`whitespace-nowrap px-3 py-2 tabular-nums ${align === 'left' ? 'text-left' : 'text-right'} ${className}`}
    >
      {children}
    </td>
  )
}
