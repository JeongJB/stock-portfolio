import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import type { PortfolioView, PositionView } from '../../api/types'
import { formatMoney, formatQty } from '../../app/format'
import {
  buildRebalanceInput,
  computeRebalance,
  type BasisMode,
  type RebalanceResult,
} from '../../lib/rebalance'

const BASIS_MODE_KEY = 'rebalance-basis-mode'

function loadBasisMode(): BasisMode {
  if (typeof window === 'undefined') return 'EX_CASH'
  const stored = window.localStorage.getItem(BASIS_MODE_KEY)
  return stored === 'INC_CASH' ? 'INC_CASH' : 'EX_CASH'
}

interface Props {
  open: boolean
  portfolio: PortfolioView
  initialTicker: string | null
  onClose: () => void
}

export function RebalanceModal({ open, portfolio, initialTicker, onClose }: Props) {
  const navigate = useNavigate()

  // 시세 실패 종목은 옵션에서 제외 — 호출부에서 비활성화되긴 하지만 dropdown 변경도 가드.
  const candidates = useMemo(
    () => portfolio.positions.filter((p) => p.lastPriceUsd != null),
    [portfolio.positions],
  )

  const [ticker, setTicker] = useState<string>(initialTicker ?? candidates[0]?.ticker ?? '')
  const [basisMode, setBasisMode] = useState<BasisMode>(loadBasisMode)
  const [targetPctInput, setTargetPctInput] = useState<string>('')
  // 계산 시점의 target/basisMode 도 함께 박아 input 이 바뀌어도 결과 카드 표시가 고정되게 한다.
  const [result, setResult] = useState<{
    computed: RebalanceResult
    targetPct: number
    basisMode: BasisMode
  } | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const selectedPosition: PositionView | undefined = useMemo(
    () => candidates.find((p) => p.ticker === ticker),
    [candidates, ticker],
  )

  const totalMarketValueUsd = Number(portfolio.totalMarketValueUsd)
  const cashUsd = Number(portfolio.cashUsd)
  const denominatorUsd = useMemo(() => {
    const stocks = Number.isFinite(totalMarketValueUsd) ? totalMarketValueUsd : 0
    const cash = Number.isFinite(cashUsd) ? cashUsd : 0
    return basisMode === 'INC_CASH' ? stocks + cash : stocks
  }, [basisMode, totalMarketValueUsd, cashUsd])
  const denominatorValid = denominatorUsd > 0
  const totalMarketValueValid = Number.isFinite(totalMarketValueUsd) && totalMarketValueUsd > 0

  const currentWeightPct = useMemo(() => {
    if (!selectedPosition || !denominatorValid) return null
    const mv = Number(selectedPosition.marketValueUsd ?? '0')
    if (!Number.isFinite(mv)) return null
    return (mv / denominatorUsd) * 100
  }, [selectedPosition, denominatorUsd, denominatorValid])

  // 모달이 열릴 때 prefill ticker (initialTicker 가 바뀌어도 동일).
  useEffect(() => {
    if (!open) return
    const next = initialTicker ?? candidates[0]?.ticker ?? ''
    setTicker(next)
    setResult(null)
    setErrorMsg(null)
  }, [open, initialTicker, candidates])

  // 종목 변경 시 목표 비중 input default = 현재 비중. 계산 결과 reset.
  useEffect(() => {
    if (!open) return
    if (currentWeightPct == null) {
      setTargetPctInput('')
      return
    }
    setTargetPctInput(currentWeightPct.toFixed(2))
    setResult(null)
    setErrorMsg(null)
  }, [open, ticker, currentWeightPct])

  // ESC 로 닫기.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  // 모달 열릴 때 body scroll lock.
  useEffect(() => {
    if (!open) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [open])

  if (!open) return null

  const usdKrwRate = Number(portfolio.usdKrwRate)
  const rateValid = Number.isFinite(usdKrwRate) && usdKrwRate > 0

  const handleCompute = () => {
    setErrorMsg(null)
    if (!selectedPosition) {
      setErrorMsg('종목을 선택하세요.')
      return
    }
    const target = Number(targetPctInput)
    if (!Number.isFinite(target) || target <= 0 || target > 100) {
      setErrorMsg('목표 비중은 0 초과 100 이하의 값이어야 합니다.')
      return
    }
    const input = buildRebalanceInput(portfolio, selectedPosition, target, basisMode)
    if (!input) {
      setErrorMsg('해당 종목의 시세를 사용할 수 없어 계산할 수 없습니다.')
      return
    }
    setResult({ computed: computeRebalance(input), targetPct: target, basisMode })
  }

  const handleBasisModeChange = (next: BasisMode) => {
    setBasisMode(next)
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(BASIS_MODE_KEY, next)
    }
    // 모드가 바뀌면 현재 비중도 바뀌므로 결과 카드는 reset (input default 도 useEffect 가 갱신).
    setResult(null)
    setErrorMsg(null)
  }

  const handleNavigate = (action: 'BUY' | 'SELL', qty: number) => {
    if (!selectedPosition) return
    const url = `/trades?type=${action}&ticker=${encodeURIComponent(selectedPosition.ticker)}&qty=${qty}`
    onClose()
    navigate(url)
  }

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="rebalance-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-3"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-5 shadow-xl dark:border-slate-700 dark:bg-slate-900"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between">
          <h2
            id="rebalance-modal-title"
            className="text-lg font-medium text-slate-900 dark:text-slate-100"
          >
            비중 리밸런싱 계산기
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="-mt-1 -mr-1 inline-flex h-8 w-8 items-center justify-center rounded text-slate-500 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100"
          >
            <CloseIcon />
          </button>
        </div>

        {!totalMarketValueValid || candidates.length === 0 ? (
          <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-800/50 dark:bg-amber-950/30 dark:text-amber-300">
            계산 가능한 보유 종목이 없습니다. (보유 종목 0개이거나 시세 조회 실패)
          </p>
        ) : (
          <div className="space-y-4">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-slate-600 dark:text-slate-300">종목</span>
              <select
                value={ticker}
                onChange={(e) => setTicker(e.target.value)}
                className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
              >
                {candidates.map((p) => (
                  <option key={p.ticker} value={p.ticker}>
                    {p.ticker} · 수량 {formatQty(p.qty)}주
                  </option>
                ))}
              </select>
            </label>

            <fieldset className="flex flex-col gap-1 text-sm">
              <legend className="text-slate-600 dark:text-slate-300">비중 계산 기준</legend>
              <div
                role="radiogroup"
                aria-label="비중 계산 기준"
                className="inline-flex rounded-md border border-slate-300 bg-white p-0.5 dark:border-slate-700 dark:bg-slate-950"
              >
                {(
                  [
                    { value: 'EX_CASH', label: '현금 제외' },
                    { value: 'INC_CASH', label: '현금 포함' },
                  ] as const
                ).map((opt) => {
                  const active = basisMode === opt.value
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      role="radio"
                      aria-checked={active}
                      onClick={() => handleBasisModeChange(opt.value)}
                      className={`min-h-[36px] flex-1 rounded px-3 py-1.5 text-xs font-medium transition-colors ${
                        active
                          ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
                          : 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800'
                      }`}
                    >
                      {opt.label}
                    </button>
                  )
                })}
              </div>
            </fieldset>

            {selectedPosition && currentWeightPct != null && (
              <div className="rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-600 dark:bg-slate-800/50 dark:text-slate-300">
                <div>
                  현재 비중:{' '}
                  <span className="font-medium text-slate-900 dark:text-slate-100">
                    {currentWeightPct.toFixed(2)}%
                  </span>
                </div>
                <div className="mt-0.5 text-[11px] text-slate-500 dark:text-slate-400">
                  {basisMode === 'INC_CASH'
                    ? `분모는 주식 + 현금 (${formatMoney(String(denominatorUsd), 'USD')})`
                    : `분모는 현금 제외 주식 가치 합계 (${formatMoney(portfolio.totalMarketValueUsd, 'USD')})`}
                </div>
              </div>
            )}

            <label className="flex flex-col gap-1 text-sm">
              <span className="text-slate-600 dark:text-slate-300">목표 비중 (%)</span>
              <input
                type="text"
                inputMode="decimal"
                value={targetPctInput}
                onChange={(e) => setTargetPctInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    handleCompute()
                  }
                }}
                placeholder="0.00"
                className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
              />
            </label>

            {errorMsg && (
              <div
                role="alert"
                className="rounded-md border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
              >
                {errorMsg}
              </div>
            )}

            <div className="flex justify-end">
              <button
                type="button"
                onClick={handleCompute}
                className="min-h-[44px] rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
              >
                계산
              </button>
            </div>

            {result && selectedPosition && (
              <ResultCard
                ticker={selectedPosition.ticker}
                result={result.computed}
                targetPct={result.targetPct}
                basisMode={result.basisMode}
                usdKrwRate={rateValid ? usdKrwRate : null}
                onNavigate={handleNavigate}
              />
            )}
          </div>
        )}

        <div className="mt-5 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="min-h-[36px] rounded border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
          >
            닫기
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}

function ResultCard({
  ticker,
  result,
  targetPct,
  basisMode,
  usdKrwRate,
  onNavigate,
}: {
  ticker: string
  result: RebalanceResult
  targetPct: number
  basisMode: BasisMode
  usdKrwRate: number | null
  onNavigate: (action: 'BUY' | 'SELL', qty: number) => void
}) {
  const isBuy = result.action === 'BUY'
  const isSell = result.action === 'SELL'
  const isNone = result.action === 'NONE'

  const actionLabel = isBuy ? '매수' : isSell ? '매도' : null
  const actionColorClass = isBuy
    ? 'text-emerald-700 dark:text-emerald-400'
    : isSell
      ? 'text-rose-700 dark:text-rose-400'
      : 'text-slate-600 dark:text-slate-300'

  const basisLabel = basisMode === 'INC_CASH' ? '현금 포함' : '현금 제외'

  const krwCost = usdKrwRate != null ? result.costUsd * usdKrwRate : null
  const krwPostCash = usdKrwRate != null ? result.postCashUsd * usdKrwRate : null

  return (
    <div className="space-y-3 rounded-md border border-slate-200 bg-slate-50 p-3 text-sm dark:border-slate-700 dark:bg-slate-800/50">
      <div className="text-center">
        {isNone ? (
          <p className="text-base font-medium text-slate-700 dark:text-slate-200">
            변동 없음 (이미 목표 비중)
          </p>
        ) : (
          <p className="text-base">
            <span className="font-mono font-semibold">{ticker}</span>{' '}
            <span className={`text-lg font-semibold ${actionColorClass}`}>
              {formatQty(String(result.qty))}주 {actionLabel}
            </span>
          </p>
        )}
        <p className="mt-1 text-[11px] text-slate-500 dark:text-slate-400">
          기준: {basisLabel}
        </p>
      </div>

      {!isNone && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
          <dt className="text-slate-500 dark:text-slate-400">예상 거래액</dt>
          <dd className="text-right tabular-nums text-slate-800 dark:text-slate-100">
            {formatMoney(String(result.costUsd), 'USD')}
            {krwCost != null && (
              <span className="ml-1 text-slate-500 dark:text-slate-400">
                ({formatMoney(String(krwCost), 'KRW')})
              </span>
            )}
          </dd>

          <dt className="text-slate-500 dark:text-slate-400">실현 비중</dt>
          <dd className="text-right tabular-nums text-slate-800 dark:text-slate-100">
            {result.realizedWeightPct.toFixed(2)}%
          </dd>

          <dt className="text-slate-500 dark:text-slate-400">목표 / 오차</dt>
          <dd className="text-right tabular-nums text-slate-800 dark:text-slate-100">
            {targetPct.toFixed(2)}% / {formatSignedPct(result.errorPct)}p
          </dd>

          {isBuy && (
            <>
              <dt className="text-slate-500 dark:text-slate-400">거래 후 현금</dt>
              <dd className="text-right tabular-nums text-slate-800 dark:text-slate-100">
                {formatMoney(String(result.postCashUsd), 'USD')}
                {krwPostCash != null && (
                  <span className="ml-1 text-slate-500 dark:text-slate-400">
                    ({formatMoney(String(krwPostCash), 'KRW')})
                  </span>
                )}
              </dd>
            </>
          )}
        </dl>
      )}

      {result.cashShortage && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:border-amber-800/50 dark:bg-amber-950/30 dark:text-amber-300">
          현금 부족 — 매수 가능 최대: {result.maxAffordableQty}주
        </div>
      )}

      {result.cappedBySellQty && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:border-amber-800/50 dark:bg-amber-950/30 dark:text-amber-300">
          보유 수량 초과 — {result.qty}주로 제한됨
        </div>
      )}

      {!isNone && (
        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => onNavigate(isBuy ? 'BUY' : 'SELL', result.qty)}
            className="min-h-[36px] rounded-md bg-slate-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
          >
            거래 입력으로 이동
          </button>
        </div>
      )}
    </div>
  )
}

function formatSignedPct(value: number): string {
  const sign = value > 0 ? '+' : value < 0 ? '-' : ''
  return `${sign}${Math.abs(value).toFixed(2)}%`
}

function CloseIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 20 20"
      fill="currentColor"
      className="h-4 w-4"
      aria-hidden="true"
    >
      <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z" />
    </svg>
  )
}
