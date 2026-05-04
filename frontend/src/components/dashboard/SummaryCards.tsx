import type { PortfolioView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import {
  formatKstDateTime,
  formatMoney,
  formatRate,
  formatRelativeMinutes,
  formatSignedMoney,
  formatSignedPercent,
  pnlColorClass,
} from '../../app/format'

interface Props {
  data: PortfolioView
}

export function SummaryCards({ data }: Props) {
  const { currency } = useCurrency()

  const totalAssets = currency === 'USD' ? data.totalAssetsUsd : data.totalAssetsKrw
  const marketValue = currency === 'USD' ? data.totalMarketValueUsd : data.totalMarketValueKrw
  const principal = currency === 'USD' ? data.principalUsd : data.principalKrw
  const pnl = currency === 'USD' ? data.totalUnrealizedPnlUsd : data.totalUnrealizedPnlKrw
  const cash = currency === 'USD' ? data.cashUsd : data.cashKrw

  return (
    <section className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2 md:grid-cols-3">
        <Card label="총액 (현금+평가액)" value={formatMoney(totalAssets, currency)} />
        <Card label="평가액" value={formatMoney(marketValue, currency)} />
        <Card label="원금" value={formatMoney(principal, currency)} />
        <Card
          label="평가손익"
          value={formatSignedMoney(pnl, currency)}
          valueClass={pnlColorClass(pnl)}
        />
        <Card
          label="연환산 수익률 (IRR)"
          value={formatSignedPercent(data.irr)}
          valueClass={pnlColorClass(data.irr)}
        />
        <Card
          label="단순 수익률"
          value={formatSignedPercent(data.simpleReturn)}
          valueClass={pnlColorClass(data.simpleReturn)}
        />
      </div>
      <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-slate-500 dark:text-slate-400">
        <span>
          현금: <span className="text-slate-700 dark:text-slate-200">{formatMoney(cash, currency)}</span>
        </span>
        <span>
          USD/KRW: <span className="text-slate-700 dark:text-slate-200">{formatRate(data.usdKrwRate)}</span>
        </span>
        <span>
          기준: <span className="text-slate-700 dark:text-slate-200">{formatKstDateTime(data.asOf)}</span>
        </span>
        <QuoteAsOfBadge data={data} />
      </div>
    </section>
  )
}

/**
 * 시세 기준 시각 + 성공/전체 종목 수 배지.
 *  - 시세 1개 이상 성공: "시세 기준: 2026-05-04 13:30 KST (12분 전)"
 *  - 성공 == 전체 종목 수면 "· M/N 종목" 생략 (간결).
 *  - 부분 실패(quoteCount < positionsCount)면 "· M/N 종목" 표시.
 *  - 0/N 전체 실패면 "시세 기준: — · 0/N 종목" (회색만, 강조 X).
 *  - 보유 0종목이면 빈 문자열로 폴백 (배지 자체 숨김).
 */
function QuoteAsOfBadge({ data }: { data: PortfolioView }) {
  if (data.positionsCount === 0) return null

  const tooltip =
    '표시된 시각은 종목별 시세 기준 시각의 최솟값입니다. ' +
    'KIS 캐시 슬롯이 KST 10분 단위라 같은 슬롯 내 재조회 시 같은 가격이 반환됩니다.'

  // 0/N 전체 실패 — 시각 자리는 dash 만.
  if (data.quoteCount === 0 || !data.quoteAsOf) {
    return (
      <span title={tooltip}>
        시세 기준:{' '}
        <span className="text-slate-700 dark:text-slate-200">—</span>
        {` · ${data.quoteCount}/${data.positionsCount} 종목`}
      </span>
    )
  }

  const datetime = formatKstDateTime(data.quoteAsOf)
  const relative = formatRelativeMinutes(data.quoteAsOf)
  const countSuffix =
    data.quoteCount < data.positionsCount
      ? ` · ${data.quoteCount}/${data.positionsCount} 종목`
      : ''
  return (
    <span title={tooltip}>
      시세 기준:{' '}
      <span className="text-slate-700 dark:text-slate-200">
        {datetime} ({relative})
      </span>
      {countSuffix}
    </span>
  )
}

function Card({
  label,
  value,
  valueClass,
}: {
  label: string
  value: string
  valueClass?: string
}) {
  return (
    // min-w-0 로 grid item 이 콘텐츠 너비를 따르지 않고 셀 폭에 맞춰 줄어들도록 허용.
    // truncate 가 동작하려면 부모 chain 모두 min-w-0 필요.
    <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {label}
      </div>
      <div
        className={`mt-1 truncate text-xl font-semibold tabular-nums ${valueClass ?? ''}`}
        title={value}
      >
        {value}
      </div>
    </div>
  )
}
