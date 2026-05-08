import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { SnapshotView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import { formatMoney, formatRate } from '../../app/format'

interface Props {
  snapshots: SnapshotView[]
}

interface ChartPoint {
  date: string
  totalAssets: number
  principal: number
  usdKrwRate: number
}

// 총액 라인은 진한 인디고, 원금 라인은 회색 점선으로 — 총액(현금+평가액)을 시각적으로 강조.
const TOTAL_ASSETS_COLOR = '#4f46e5'
const PRINCIPAL_COLOR = '#94a3b8'

export function SnapshotTrendChart({ snapshots }: Props) {
  const { currency } = useCurrency()

  // 같은 응답에서 두 통화 모두 오므로 통화 토글 시 재페치 없이 매핑만 새로 한다.
  const points: ChartPoint[] = snapshots.map((s) => ({
    date: s.date,
    totalAssets: Number(
      currency === 'USD' ? s.totalAssetsUsd : s.totalAssetsKrw,
    ),
    principal: Number(currency === 'USD' ? s.principalUsd : s.principalKrw),
    usdKrwRate: Number(s.usdKrwRate),
  }))

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <h3 className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">
        총액 vs 원금 추이
      </h3>
      <div className="h-80">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-slate-200 dark:stroke-slate-700" />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 11 }}
              minTickGap={24}
            />
            <YAxis
              tick={{ fontSize: 11 }}
              tickFormatter={(v: number) => compactNumber(v, currency)}
              width={72}
              domain={[
                (dataMin: number) => dataMin * 0.97,
                (dataMax: number) => dataMax * 1.03,
              ]}
              allowDecimals={false}
            />
            <Tooltip
              content={<TrendTooltip currency={currency} />}
            />
            <Legend />
            <Line
              type="monotone"
              dataKey="totalAssets"
              name={`총액 (${currency})`}
              stroke={TOTAL_ASSETS_COLOR}
              strokeWidth={2}
              dot={{ r: 2 }}
              activeDot={{ r: 5 }}
              isAnimationActive={false}
            />
            <Line
              type="monotone"
              dataKey="principal"
              name={`원금 (${currency})`}
              stroke={PRINCIPAL_COLOR}
              strokeWidth={2}
              strokeDasharray="4 4"
              dot={{ r: 2 }}
              activeDot={{ r: 5 }}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}

interface TooltipPayloadItem {
  dataKey?: string
  value?: number
  payload?: ChartPoint
}

function TrendTooltip({
  active,
  payload,
  label,
  currency,
}: {
  active?: boolean
  payload?: TooltipPayloadItem[]
  label?: string
  currency: 'USD' | 'KRW'
}) {
  if (!active || !payload || payload.length === 0) return null
  const point = payload[0]?.payload
  if (!point) return null

  return (
    <div className="rounded-md border border-slate-300 bg-white px-3 py-2 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900">
      <div className="mb-1 font-medium text-slate-700 dark:text-slate-200">{label}</div>
      <div className="space-y-0.5 tabular-nums">
        <div>
          <span className="inline-block h-2 w-2 rounded-sm align-middle" style={{ backgroundColor: TOTAL_ASSETS_COLOR }} />{' '}
          총액: <span className="text-slate-700 dark:text-slate-200">{formatMoney(String(point.totalAssets), currency)}</span>
        </div>
        <div>
          <span className="inline-block h-2 w-2 rounded-sm align-middle" style={{ backgroundColor: PRINCIPAL_COLOR }} />{' '}
          원금: <span className="text-slate-700 dark:text-slate-200">{formatMoney(String(point.principal), currency)}</span>
        </div>
        <div className="pt-1 text-slate-500 dark:text-slate-400">
          USD/KRW: <span className="text-slate-700 dark:text-slate-200">{formatRate(String(point.usdKrwRate))}</span>
        </div>
      </div>
    </div>
  )
}

// Y축 눈금 압축 표기. KRW 는 자릿수가 커서 만/억 단위로 줄여 가독성 확보.
function compactNumber(n: number, currency: 'USD' | 'KRW'): string {
  if (!Number.isFinite(n)) return ''
  const abs = Math.abs(n)
  if (currency === 'KRW') {
    if (abs >= 1_0000_0000) return `${(n / 1_0000_0000).toFixed(2)}억`
    if (abs >= 1_0000) return `${(n / 1_0000).toFixed(0)}만`
    return n.toFixed(0)
  }
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (abs >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return n.toFixed(0)
}
