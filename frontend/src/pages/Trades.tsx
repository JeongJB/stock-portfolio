import { TradeForm } from '../components/trades/TradeForm'

export function Trades() {
  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h2 className="text-xl font-medium">거래</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          매수/매도/입금/출금을 직접 입력합니다. 입금·출금은 USD 현금 잔고에, 매수·매도는
          현금과 보유 포지션에 자동 반영됩니다.
        </p>
      </div>
      <TradeForm />
    </section>
  )
}
