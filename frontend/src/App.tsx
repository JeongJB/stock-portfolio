import { Routes, Route, Link } from 'react-router-dom'

function Dashboard() {
  return (
    <section className="space-y-2">
      <h2 className="text-xl font-medium">대시보드</h2>
      <p className="text-slate-600 dark:text-slate-300">
        파이 차트와 평가자산 vs 원금이 들어갈 자리.
      </p>
    </section>
  )
}

function Trades() {
  return (
    <section className="space-y-2">
      <h2 className="text-xl font-medium">거래</h2>
      <p className="text-slate-600 dark:text-slate-300">
        DEPOSIT / WITHDRAW / BUY / SELL 입력 폼이 들어갈 자리.
      </p>
    </section>
  )
}

function Snapshots() {
  return (
    <section className="space-y-2">
      <h2 className="text-xl font-medium">스냅샷 추이</h2>
      <p className="text-slate-600 dark:text-slate-300">
        총평가액 시계열 라인 차트가 들어갈 자리.
      </p>
    </section>
  )
}

export default function App() {
  return (
    <div className="mx-auto max-w-5xl p-6 space-y-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold">Stock Portfolio</h1>
        <nav className="flex gap-4 text-sm">
          <Link to="/" className="hover:underline">
            대시보드
          </Link>
          <Link to="/trades" className="hover:underline">
            거래
          </Link>
          <Link to="/snapshots" className="hover:underline">
            스냅샷
          </Link>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/trades" element={<Trades />} />
          <Route path="/snapshots" element={<Snapshots />} />
        </Routes>
      </main>
    </div>
  )
}
