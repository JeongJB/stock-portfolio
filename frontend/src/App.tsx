import { lazy, Suspense } from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { CurrencyToggle } from './components/CurrencyToggle'
import { InstallButton } from './components/InstallButton'

const Dashboard = lazy(() =>
  import('./pages/Dashboard').then((m) => ({ default: m.Dashboard })),
)
const Trades = lazy(() =>
  import('./pages/Trades').then((m) => ({ default: m.Trades })),
)
const Snapshots = lazy(() =>
  import('./pages/Snapshots').then((m) => ({ default: m.Snapshots })),
)
const History = lazy(() =>
  import('./pages/History').then((m) => ({ default: m.History })),
)
const Pnl = lazy(() => import('./pages/Pnl').then((m) => ({ default: m.Pnl })))

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  isActive
    ? 'font-medium text-slate-900 dark:text-slate-100'
    : 'text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'

function RouteFallback() {
  return (
    <p className="text-xs text-slate-500 dark:text-slate-400">로딩 중...</p>
  )
}

export default function App() {
  return (
    <div className="mx-auto max-w-5xl space-y-6 p-3 sm:p-6">
      <header className="flex flex-wrap items-center justify-between gap-2 sm:gap-3">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 sm:gap-x-6">
          <h1 className="text-lg font-semibold sm:text-2xl">Stock Portfolio</h1>
          <nav className="flex flex-wrap gap-x-3 gap-y-1 text-sm sm:gap-4">
            <NavLink to="/" end className={navLinkClass}>
              대시보드
            </NavLink>
            <NavLink to="/trades" className={navLinkClass}>
              거래 입력
            </NavLink>
            <NavLink to="/snapshots" className={navLinkClass}>
              스냅샷
            </NavLink>
            <NavLink to="/history" className={navLinkClass}>
              거래 내역
            </NavLink>
            <NavLink to="/pnl" className={navLinkClass}>
              손익
            </NavLink>
          </nav>
        </div>
        <div className="flex items-center gap-2">
          <InstallButton />
          <CurrencyToggle />
        </div>
      </header>
      <main>
        <Suspense fallback={<RouteFallback />}>
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/trades" element={<Trades />} />
            <Route path="/snapshots" element={<Snapshots />} />
            <Route path="/history" element={<History />} />
            <Route path="/pnl" element={<Pnl />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  )
}
