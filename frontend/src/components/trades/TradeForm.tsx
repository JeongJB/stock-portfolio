import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { recordTrade } from '../../api/client'
import type { PositionView, RecordTradeRequest, TradeType } from '../../api/types'
import { useToast } from '../../app/toastContext'
import { formatMoney, formatQty } from '../../app/format'
import { useOwnedPositions } from './useOwnedPositions'

const TRADE_TYPES: { type: TradeType; label: string }[] = [
  { type: 'BUY', label: '매수' },
  { type: 'SELL', label: '매도' },
  { type: 'DIVIDEND', label: '배당' },
  { type: 'DEPOSIT', label: '입금' },
  { type: 'WITHDRAW', label: '출금' },
]

interface FormFields {
  ticker: string
  qty: string
  price: string
  fee: string
  cashAmount: string
  memo: string
  sector: string
}

const EMPTY_FIELDS: FormFields = {
  ticker: '',
  qty: '',
  price: '',
  fee: '',
  cashAmount: '',
  memo: '',
  sector: '',
}

const MEMO_MAX_LENGTH = 200
const SECTOR_MAX_LENGTH = 30

// 양의 십진수만 허용. 빈 문자열은 OK(미입력 상태).
const DECIMAL_RE = /^\d*\.?\d*$/

type BuyMode = 'new' | 'add'

function isAssetTrade(type: TradeType): boolean {
  return type === 'BUY' || type === 'SELL'
}

function isDividend(type: TradeType): boolean {
  return type === 'DIVIDEND'
}

export function TradeForm() {
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const { positions, isLoading: positionsLoading } = useOwnedPositions()
  const [searchParams] = useSearchParams()

  const [type, setType] = useState<TradeType>('BUY')
  const [fields, setFields] = useState<FormFields>(EMPTY_FIELDS)
  const [buyMode, setBuyMode] = useState<BuyMode>('new')
  const [pastTimeOpen, setPastTimeOpen] = useState(false)
  const [executedAtLocal, setExecutedAtLocal] = useState('') // datetime-local 값
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  // URL 쿼리 prefill: ?type=BUY|SELL&ticker=AAPL&qty=12.
  // BUY 일 때 ticker 가 보유 종목이면 "추가 매수" 모드 + dropdown 선택, 아니면 "신규 매수" 자유 입력.
  // positions 가 로드된 뒤에야 buyMode 결정이 가능하므로 effect 안에 가드. ref 로 1회만 적용.
  const prefilledRef = useRef(false)
  useEffect(() => {
    if (prefilledRef.current) return
    if (positionsLoading) return
    const qpType = searchParams.get('type')
    const qpTicker = searchParams.get('ticker')
    const qpQty = searchParams.get('qty')
    if (qpType !== 'BUY' && qpType !== 'SELL') {
      prefilledRef.current = true
      return
    }
    const ticker = qpTicker?.toUpperCase() ?? ''
    const isOwned = ticker
      ? positions.some((p) => p.ticker.toUpperCase() === ticker)
      : false
    setType(qpType)
    if (qpType === 'BUY') {
      setBuyMode(isOwned ? 'add' : 'new')
    }
    const sector =
      qpType === 'BUY'
        ? (positions.find((p) => p.ticker.toUpperCase() === ticker)?.sector ?? '')
        : ''
    setFields({
      ...EMPTY_FIELDS,
      ticker,
      qty: qpQty ?? '',
      sector: typeof sector === 'string' ? sector : '',
    })
    prefilledRef.current = true
  }, [positionsLoading, positions, searchParams])

  const mutation = useMutation({
    mutationFn: recordTrade,
    onSuccess: () => {
      showToast('거래가 기록되었습니다', 'success')
      // ticker/sector 는 보존하고 금액·memo 필드만 비운다(연속 입력 편의).
      setFields((prev) => ({
        ticker: prev.ticker,
        qty: '',
        price: '',
        fee: '',
        cashAmount: '',
        memo: '',
        sector: prev.sector,
      }))
      setErrorMessage(null)
      void queryClient.invalidateQueries({ queryKey: ['portfolio'] })
      void queryClient.invalidateQueries({ queryKey: ['snapshots'] })
      void queryClient.invalidateQueries({ queryKey: ['trades'] })
    },
    onError: (err: Error) => {
      setErrorMessage(err.message)
    },
  })

  // 보유 종목의 distinct sector 목록 (BUY 탭의 datalist 옵션). 빈 값/null 은 제외.
  const sectorSuggestions = Array.from(
    new Set(
      positions
        .map((p) => p.sector)
        .filter((s): s is string => typeof s === 'string' && s.trim().length > 0),
    ),
  ).sort()

  const handleTypeChange = (next: TradeType) => {
    if (next === type) return
    setType(next)
    // 탭 전환 시 입력값 초기화 — 의미가 다른 폼이므로 잔여값 보존이 오히려 혼동을 유발.
    setFields(EMPTY_FIELDS)
    setBuyMode('new')
    setErrorMessage(null)
  }

  const handleBuyModeChange = (next: BuyMode) => {
    if (next === buyMode) return
    setBuyMode(next)
    // 모드 전환 시 ticker 만 리셋 (qty/price 는 이번 거래 값이라 어차피 비워두는 패턴).
    setFields((prev) => ({ ...prev, ticker: '' }))
    setErrorMessage(null)
  }

  const handleFieldChange = (key: keyof FormFields, raw: string) => {
    if (key === 'ticker') {
      setFields((prev) => ({ ...prev, ticker: raw.toUpperCase() }))
      return
    }
    if (key === 'memo') {
      // 200자 초과는 클라이언트에서 잘라낸다 — 서버 검증과 동일 한계.
      const clipped = raw.length > MEMO_MAX_LENGTH ? raw.slice(0, MEMO_MAX_LENGTH) : raw
      setFields((prev) => ({ ...prev, memo: clipped }))
      return
    }
    if (key === 'sector') {
      // 30자 초과는 클라이언트에서 잘라낸다.
      const clipped = raw.length > SECTOR_MAX_LENGTH ? raw.slice(0, SECTOR_MAX_LENGTH) : raw
      setFields((prev) => ({ ...prev, sector: clipped }))
      return
    }
    if (raw !== '' && !DECIMAL_RE.test(raw)) return
    setFields((prev) => ({ ...prev, [key]: raw }))
  }

  // ticker 가 보유 종목과 일치하면 sector 를 그 종목 값으로 pre-fill
  // BUY 탭에서만 의미 있음 — SELL/DIVIDEND 등 sector 미노출 탭에서는 fields.sector 가 사용되지 않는다.
  const prefillSectorFromOwnedTicker = (ticker: string) => {
    if (!ticker) return
    const match = positions.find(
      (p) => p.ticker.toUpperCase() === ticker.toUpperCase(),
    )
    const matchSector = match?.sector
    if (typeof matchSector !== 'string' || matchSector.trim().length === 0) return
    setFields((prev) => ({ ...prev, sector: matchSector }))
  }

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (mutation.isPending) return
    setErrorMessage(null)

    const req: RecordTradeRequest = { type }

    if (pastTimeOpen && executedAtLocal) {
      // datetime-local 은 사용자 로컬 타임존으로 해석된다(이 앱은 KST 1인용).
      const parsed = new Date(executedAtLocal)
      if (!Number.isNaN(parsed.getTime())) {
        req.executedAt = parsed.toISOString()
      }
    }

    if (isAssetTrade(type)) {
      if (fields.ticker.trim()) req.ticker = fields.ticker.trim()
      if (fields.qty) req.qty = fields.qty
      if (fields.price) req.price = fields.price
      if (fields.fee) req.fee = fields.fee
      // sector 는 BUY 탭에서만 페이로드에 포함. SELL 은 무관.
      if (type === 'BUY') {
        const trimmedSector = fields.sector.trim()
        if (trimmedSector) req.sector = trimmedSector
      }
    } else if (isDividend(type)) {
      if (fields.ticker.trim()) req.ticker = fields.ticker.trim()
      if (fields.cashAmount) req.cashAmount = fields.cashAmount
    } else {
      // DEPOSIT / WITHDRAW
      if (fields.cashAmount) req.cashAmount = fields.cashAmount
      const trimmedMemo = fields.memo.trim()
      if (trimmedMemo) req.memo = trimmedMemo
    }

    mutation.mutate(req)
  }

  const isPending = mutation.isPending
  const selectedPosition = fields.ticker
    ? positions.find((p) => p.ticker === fields.ticker)
    : undefined

  return (
    <form
      onSubmit={handleSubmit}
      className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
    >
      <TradeTypeTabs type={type} onChange={handleTypeChange} disabled={isPending} />

      {type === 'BUY' && (
        <BuyModeRadio mode={buyMode} onChange={handleBuyModeChange} disabled={isPending} />
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        {type === 'BUY' ? (
          <>
            {buyMode === 'new' ? (
              <FieldText
                label="티커"
                value={fields.ticker}
                onChange={(v) => handleFieldChange('ticker', v)}
                onBlur={() => prefillSectorFromOwnedTicker(fields.ticker)}
                placeholder="예: AAPL"
                autoComplete="off"
                disabled={isPending}
              />
            ) : (
              <FieldPositionSelect
                label="보유 종목"
                value={fields.ticker}
                onChange={(v) => {
                  handleFieldChange('ticker', v)
                  // 보유 종목 dropdown 은 선택 즉시 sector pre-fill (사용자 추가 액션 없이 합리적 기본값 제공).
                  prefillSectorFromOwnedTicker(v)
                }}
                positions={positions}
                disabled={isPending}
                loading={positionsLoading}
                emptyMessage="보유 중인 종목이 없습니다 — 신규 매수로 전환하세요"
                placeholder="보유 종목 선택"
                selectedPosition={selectedPosition}
              />
            )}
            <FieldDecimal
              label="수량"
              value={fields.qty}
              onChange={(v) => handleFieldChange('qty', v)}
              placeholder="0"
              disabled={isPending}
            />
            <FieldDecimal
              label="단가 (USD)"
              value={fields.price}
              onChange={(v) => handleFieldChange('price', v)}
              placeholder="0.00"
              disabled={isPending}
            />
            <FieldDecimal
              label="수수료 (USD, 선택)"
              value={fields.fee}
              onChange={(v) => handleFieldChange('fee', v)}
              placeholder="0.00"
              disabled={isPending}
            />
            <FieldSector
              value={fields.sector}
              onChange={(v) => handleFieldChange('sector', v)}
              suggestions={sectorSuggestions}
              disabled={isPending}
              maxLength={SECTOR_MAX_LENGTH}
            />
          </>
        ) : type === 'SELL' ? (
          <>
            <FieldPositionSelect
              label="보유 종목"
              value={fields.ticker}
              onChange={(v) => handleFieldChange('ticker', v)}
              positions={positions}
              disabled={isPending}
              loading={positionsLoading}
              emptyMessage="매도 가능한 종목이 없습니다"
              placeholder="매도할 종목 선택"
              selectedPosition={selectedPosition}
            />
            <FieldDecimal
              label="수량"
              value={fields.qty}
              onChange={(v) => handleFieldChange('qty', v)}
              placeholder="0"
              disabled={isPending}
            />
            <FieldDecimal
              label="단가 (USD)"
              value={fields.price}
              onChange={(v) => handleFieldChange('price', v)}
              placeholder="0.00"
              disabled={isPending}
            />
            <FieldDecimal
              label="수수료 (USD, 선택)"
              value={fields.fee}
              onChange={(v) => handleFieldChange('fee', v)}
              placeholder="0.00"
              disabled={isPending}
            />
          </>
        ) : isDividend(type) ? (
          <>
            <FieldPositionSelect
              label="보유 종목"
              value={fields.ticker}
              onChange={(v) => handleFieldChange('ticker', v)}
              positions={positions}
              disabled={isPending}
              loading={positionsLoading}
              emptyMessage="배당 입력 가능한 종목이 없습니다"
              placeholder="배당 받은 종목 선택"
              selectedPosition={selectedPosition}
            />
            <FieldDecimal
              label="배당금 (USD, 세후 입금액)"
              value={fields.cashAmount}
              onChange={(v) => handleFieldChange('cashAmount', v)}
              placeholder="0.00"
              disabled={isPending}
            />
          </>
        ) : (
          <>
            <FieldDecimal
              label="금액 (USD)"
              value={fields.cashAmount}
              onChange={(v) => handleFieldChange('cashAmount', v)}
              placeholder="0.00"
              disabled={isPending}
            />
            <FieldMemo
              value={fields.memo}
              onChange={(v) => handleFieldChange('memo', v)}
              disabled={isPending}
              maxLength={MEMO_MAX_LENGTH}
            />
          </>
        )}
      </div>

      <ExecutedAtControl
        open={pastTimeOpen}
        value={executedAtLocal}
        onToggle={() => {
          setPastTimeOpen((prev) => {
            const next = !prev
            if (!next) setExecutedAtLocal('')
            return next
          })
        }}
        onChange={setExecutedAtLocal}
        disabled={isPending}
      />

      {errorMessage && (
        <div
          role="alert"
          className="rounded-md border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {errorMessage}
        </div>
      )}

      <div className="flex justify-end">
        <button
          type="submit"
          disabled={isPending}
          className="min-h-[44px] rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:bg-slate-400 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300 dark:disabled:bg-slate-600"
        >
          {isPending ? '기록 중...' : '거래 기록'}
        </button>
      </div>
    </form>
  )
}

function TradeTypeTabs({
  type,
  onChange,
  disabled,
}: {
  type: TradeType
  onChange: (t: TradeType) => void
  disabled: boolean
}) {
  // 5개 탭이 모바일 375px 폭을 넘을 수 있어 외부 wrapper 에 가로 스크롤 허용.
  return (
    <div className="-mx-1 overflow-x-auto px-1">
      <div
        role="tablist"
        aria-label="거래 종류"
        className="inline-flex rounded-md border border-slate-300 bg-white p-0.5 text-sm font-medium dark:border-slate-700 dark:bg-slate-900"
      >
        {TRADE_TYPES.map(({ type: t, label }) => {
          const active = type === t
          return (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={active}
              disabled={disabled}
              onClick={() => onChange(t)}
              className={
                active
                  ? 'min-h-[44px] whitespace-nowrap rounded-sm bg-slate-900 px-3 py-1.5 text-white dark:bg-slate-100 dark:text-slate-900'
                  : 'min-h-[44px] whitespace-nowrap rounded-sm px-3 py-1.5 text-slate-600 hover:text-slate-900 disabled:opacity-50 dark:text-slate-400 dark:hover:text-slate-100'
              }
            >
              {label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

function BuyModeRadio({
  mode,
  onChange,
  disabled,
}: {
  mode: BuyMode
  onChange: (m: BuyMode) => void
  disabled: boolean
}) {
  return (
    <div
      role="radiogroup"
      aria-label="매수 모드"
      className="flex items-center gap-4 text-sm text-slate-700 dark:text-slate-200"
    >
      {(
        [
          { value: 'new' as BuyMode, label: '신규 매수' },
          { value: 'add' as BuyMode, label: '추가 매수' },
        ]
      ).map(({ value, label }) => (
        <label key={value} className="inline-flex min-h-[44px] cursor-pointer items-center gap-2 py-1">
          <input
            type="radio"
            name="buyMode"
            value={value}
            checked={mode === value}
            onChange={() => onChange(value)}
            disabled={disabled}
            className="h-5 w-5 accent-slate-900 dark:accent-slate-100"
          />
          <span>{label}</span>
        </label>
      ))}
    </div>
  )
}

function FieldText({
  label,
  value,
  onChange,
  onBlur,
  placeholder,
  autoComplete,
  disabled,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  onBlur?: () => void
  placeholder?: string
  autoComplete?: string
  disabled?: boolean
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-slate-600 dark:text-slate-300">{label}</span>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={placeholder}
        autoComplete={autoComplete}
        disabled={disabled}
        className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
      />
    </label>
  )
}

function FieldSector({
  value,
  onChange,
  suggestions,
  disabled,
  maxLength,
}: {
  value: string
  onChange: (v: string) => void
  suggestions: string[]
  disabled?: boolean
  maxLength: number
}) {
  return (
    <label className="flex flex-col gap-1 text-sm sm:col-span-2">
      <span className="flex items-center justify-between text-slate-600 dark:text-slate-300">
        <span>분류 (선택, 최대 {maxLength}자)</span>
        <span className="text-xs text-slate-400 dark:text-slate-500">
          {value.length}/{maxLength}
        </span>
      </span>
      <input
        type="text"
        list="sector-suggestions"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="예: Big Tech, 반도체, 고배당"
        autoComplete="off"
        maxLength={maxLength}
        disabled={disabled}
        className="rounded-md border border-slate-300 bg-white px-3 py-2 text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
      />
      <datalist id="sector-suggestions">
        {suggestions.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
      <span className="text-xs text-slate-500 dark:text-slate-400">
        비워두면 기존 분류 유지. 새 종목은 미지정 상태.
      </span>
    </label>
  )
}

function FieldPositionSelect({
  label,
  value,
  onChange,
  positions,
  disabled,
  loading,
  emptyMessage,
  placeholder,
  selectedPosition,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  positions: PositionView[]
  disabled?: boolean
  loading?: boolean
  emptyMessage: string
  placeholder: string
  selectedPosition?: PositionView
}) {
  const isEmpty = !loading && positions.length === 0
  const selectDisabled = disabled || loading || isEmpty
  const placeholderText = loading ? '로딩 중...' : isEmpty ? emptyMessage : placeholder

  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-slate-600 dark:text-slate-300">{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={selectDisabled}
        className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 disabled:text-slate-400 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
      >
        <option value="">{placeholderText}</option>
        {positions.map((p) => (
          <option key={p.ticker} value={p.ticker}>
            {p.ticker} · 수량 {formatQty(p.qty)}주 · 평균단가 {formatMoney(p.avgCostUsd, 'USD')}
          </option>
        ))}
      </select>
      {selectedPosition && (
        <span className="text-xs text-slate-500 dark:text-slate-400">
          수량 {formatQty(selectedPosition.qty)}주 · 평균단가{' '}
          {formatMoney(selectedPosition.avgCostUsd, 'USD')}
        </span>
      )}
      {isEmpty && (
        <span className="text-xs text-slate-500 dark:text-slate-400">{emptyMessage}</span>
      )}
    </label>
  )
}

function FieldDecimal({
  label,
  value,
  onChange,
  placeholder,
  disabled,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  disabled?: boolean
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-slate-600 dark:text-slate-300">{label}</span>
      <input
        type="text"
        inputMode="decimal"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
      />
    </label>
  )
}

function FieldMemo({
  value,
  onChange,
  disabled,
  maxLength,
}: {
  value: string
  onChange: (v: string) => void
  disabled?: boolean
  maxLength: number
}) {
  return (
    <label className="flex flex-col gap-1 text-sm sm:col-span-2">
      <span className="flex items-center justify-between text-slate-600 dark:text-slate-300">
        <span>비고 (선택, 최대 {maxLength}자)</span>
        <span className="text-xs text-slate-400 dark:text-slate-500">
          {value.length}/{maxLength}
        </span>
      </span>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        rows={2}
        maxLength={maxLength}
        placeholder="예: 월급 입금, 비상금 출금"
        className="rounded-md border border-slate-300 bg-white px-3 py-2 text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
      />
    </label>
  )
}

function ExecutedAtControl({
  open,
  value,
  onToggle,
  onChange,
  disabled,
}: {
  open: boolean
  value: string
  onToggle: () => void
  onChange: (v: string) => void
  disabled?: boolean
}) {
  return (
    <div className="space-y-2 text-xs text-slate-500 dark:text-slate-400">
      <div className="flex items-center gap-3">
        <span>
          거래 시각:{' '}
          <span className="text-slate-700 dark:text-slate-200">
            {open && value ? '입력 시각 사용' : '지금'}
          </span>
        </span>
        <button
          type="button"
          onClick={onToggle}
          disabled={disabled}
          className="min-h-[36px] rounded border border-slate-300 px-2 py-1 text-slate-600 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          {open ? '지금으로 되돌리기' : '과거 시각 입력'}
        </button>
      </div>
      {open && (
        <input
          type="datetime-local"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          className="rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
        />
      )}
    </div>
  )
}
