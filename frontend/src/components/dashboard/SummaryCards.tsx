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

  const marketValue = currency === 'USD' ? data.totalMarketValueUsd : data.totalMarketValueKrw
  const principal = currency === 'USD' ? data.principalUsd : data.principalKrw
  const pnl = currency === 'USD' ? data.totalUnrealizedPnlUsd : data.totalUnrealizedPnlKrw
  const cash = currency === 'USD' ? data.cashUsd : data.cashKrw

  return (
    <section className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5">
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
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {label}
      </div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${valueClass ?? ''}`}>
        {value}
      </div>
    </div>
  )
}
