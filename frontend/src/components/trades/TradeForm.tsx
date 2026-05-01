import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { recordTrade } from '../../api/client'
import type { RecordTradeRequest, TradeType } from '../../api/types'
import { useToast } from '../../app/toastContext'

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
}

const EMPTY_FIELDS: FormFields = {
  ticker: '',
  qty: '',
  price: '',
  fee: '',
  cashAmount: '',
}

// 양의 십진수만 허용. 빈 문자열은 OK(미입력 상태).
const DECIMAL_RE = /^\d*\.?\d*$/

function isAssetTrade(type: TradeType): boolean {
  return type === 'BUY' || type === 'SELL'
}

function isDividend(type: TradeType): boolean {
  return type === 'DIVIDEND'
}

export function TradeForm() {
  const queryClient = useQueryClient()
  const { showToast } = useToast()

  const [type, setType] = useState<TradeType>('BUY')
  const [fields, setFields] = useState<FormFields>(EMPTY_FIELDS)
  const [pastTimeOpen, setPastTimeOpen] = useState(false)
  const [executedAtLocal, setExecutedAtLocal] = useState('') // datetime-local 값
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: recordTrade,
    onSuccess: () => {
      showToast('거래가 기록되었습니다', 'success')
      // ticker는 보존하고 금액 필드만 비운다(연속 입력 편의).
      setFields((prev) => ({
        ticker: prev.ticker,
        qty: '',
        price: '',
        fee: '',
        cashAmount: '',
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

  const handleTypeChange = (next: TradeType) => {
    if (next === type) return
    setType(next)
    // 탭 전환 시 입력값 초기화 — 의미가 다른 폼이므로 잔여값 보존이 오히려 혼동을 유발.
    setFields(EMPTY_FIELDS)
    setErrorMessage(null)
  }

  const handleFieldChange = (key: keyof FormFields, raw: string) => {
    if (key === 'ticker') {
      setFields((prev) => ({ ...prev, ticker: raw.toUpperCase() }))
      return
    }
    if (raw !== '' && !DECIMAL_RE.test(raw)) return
    setFields((prev) => ({ ...prev, [key]: raw }))
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
    } else if (isDividend(type)) {
      if (fields.ticker.trim()) req.ticker = fields.ticker.trim()
      if (fields.cashAmount) req.cashAmount = fields.cashAmount
    } else {
      if (fields.cashAmount) req.cashAmount = fields.cashAmount
    }

    mutation.mutate(req)
  }

  const isPending = mutation.isPending

  return (
    <form
      onSubmit={handleSubmit}
      className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
    >
      <TradeTypeTabs type={type} onChange={handleTypeChange} disabled={isPending} />

      <div className="grid gap-3 sm:grid-cols-2">
        {isAssetTrade(type) ? (
          <>
            <FieldText
              label="티커"
              value={fields.ticker}
              onChange={(v) => handleFieldChange('ticker', v)}
              placeholder="예: AAPL"
              autoComplete="off"
              disabled={isPending}
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
            <FieldText
              label="티커"
              value={fields.ticker}
              onChange={(v) => handleFieldChange('ticker', v)}
              placeholder="예: AAPL"
              autoComplete="off"
              disabled={isPending}
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
          <FieldDecimal
            label="금액 (USD)"
            value={fields.cashAmount}
            onChange={(v) => handleFieldChange('cashAmount', v)}
            placeholder="0.00"
            disabled={isPending}
          />
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
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:bg-slate-400 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300 dark:disabled:bg-slate-600"
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
  return (
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
                ? 'rounded-sm bg-slate-900 px-3 py-1.5 text-white dark:bg-slate-100 dark:text-slate-900'
                : 'rounded-sm px-3 py-1.5 text-slate-600 hover:text-slate-900 disabled:opacity-50 dark:text-slate-400 dark:hover:text-slate-100'
            }
          >
            {label}
          </button>
        )
      })}
    </div>
  )
}

function FieldText({
  label,
  value,
  onChange,
  placeholder,
  autoComplete,
  disabled,
}: {
  label: string
  value: string
  onChange: (v: string) => void
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
        placeholder={placeholder}
        autoComplete={autoComplete}
        disabled={disabled}
        className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
      />
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
          className="rounded border border-slate-300 px-2 py-0.5 text-slate-600 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
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
