import type { Currency } from '../api/types'

const USD_FMT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const KRW_FMT = new Intl.NumberFormat('ko-KR', {
  maximumFractionDigits: 0,
})

const PERCENT_FMT = new Intl.NumberFormat('en-US', {
  style: 'percent',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const QTY_FMT = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 6,
})

const KST_DATETIME_FMT = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

// BigDecimal 문자열을 Number 로 변환. null/undefined/빈 문자열은 NaN.
function toNumber(value: string | null | undefined): number {
  if (value == null || value === '') return Number.NaN
  return Number(value)
}

export function formatMoney(value: string | null | undefined, currency: Currency): string {
  const n = toNumber(value)
  if (Number.isNaN(n)) return '—'
  const symbol = currency === 'USD' ? '$' : '₩'
  const fmt = currency === 'USD' ? USD_FMT : KRW_FMT
  return `${symbol}${fmt.format(n)}`
}

// 부호 포함(평가손익용). +$123.45 / -₩1,234
export function formatSignedMoney(
  value: string | null | undefined,
  currency: Currency,
): string {
  const n = toNumber(value)
  if (Number.isNaN(n)) return '—'
  const symbol = currency === 'USD' ? '$' : '₩'
  const fmt = currency === 'USD' ? USD_FMT : KRW_FMT
  const sign = n > 0 ? '+' : n < 0 ? '-' : ''
  return `${sign}${symbol}${fmt.format(Math.abs(n))}`
}

export function formatPercent(value: string | null | undefined): string {
  const n = toNumber(value)
  if (Number.isNaN(n)) return '—'
  return PERCENT_FMT.format(n)
}

export function formatQty(value: string | null | undefined): string {
  const n = toNumber(value)
  if (Number.isNaN(n)) return '—'
  return QTY_FMT.format(n)
}

// 평가손익 부호에 따른 텍스트 색상 클래스 (Tailwind v4).
export function pnlColorClass(value: string | null | undefined): string {
  const n = toNumber(value)
  if (Number.isNaN(n) || n === 0) return 'text-slate-500 dark:text-slate-400'
  return n > 0
    ? 'text-emerald-600 dark:text-emerald-400'
    : 'text-rose-600 dark:text-rose-400'
}

// "2026-04-28 22:14 KST" 형태로 포맷.
export function formatKstDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  // ko-KR 출력은 "2026. 04. 28. 22:14" 형태가 되어 가독성이 떨어지므로 직접 조립.
  const parts = KST_DATETIME_FMT.formatToParts(d)
  const lookup = Object.fromEntries(parts.map((p) => [p.type, p.value]))
  return `${lookup.year}-${lookup.month}-${lookup.day} ${lookup.hour}:${lookup.minute} KST`
}

export function formatRate(value: string | null | undefined): string {
  const n = toNumber(value)
  if (Number.isNaN(n)) return '—'
  return KRW_FMT.format(n)
}
