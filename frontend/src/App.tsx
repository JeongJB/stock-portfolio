import { NavLink, Route, Routes } from 'react-router-dom'
import { CurrencyToggle } from './components/CurrencyToggle'
import { Dashboard } from './pages/Dashboard'
import { Trades } from './pages/Trades'
import { Snapshots } from './pages/Snapshots'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  isActive
    ? 'font-medium text-slate-900 dark:text-slate-100'
    : 'text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'

export default function App() {
  return (
    <div className="mx-auto max-w-5xl space-y-6 p-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-6">
          <h1 className="text-2xl font-semibold">Stock Portfolio</h1>
          <nav className="flex gap-4 text-sm">
            <NavLink to="/" end className={navLinkClass}>
              대시보드
            </NavLink>
            <NavLink to="/trades" className={navLinkClass}>
              거래
            </NavLink>
            <NavLink to="/snapshots" className={navLinkClass}>
              스냅샷
            </NavLink>
          </nav>
        </div>
        <CurrencyToggle />
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
