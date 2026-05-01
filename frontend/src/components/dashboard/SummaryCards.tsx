import type { PortfolioView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import {
  formatKstDateTime,
  formatMoney,
  formatRate,
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
      </div>
    </section>
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
