import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateSector } from '../api/client'
import { useToast } from '../app/toastContext'
import { useOwnedPositions } from '../components/trades/useOwnedPositions'

const SECTOR_MAX_LENGTH = 30

export function Sectors() {
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const { positions, isLoading } = useOwnedPositions()

  const [ticker, setTicker] = useState<string>('')
  const [sector, setSector] = useState<string>('')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  // 평가액 내림차순. null 행은 맨 뒤. PositionsTable 과 동일 정렬 정책.
  const sortedPositions = useMemo(() => {
    const copy = [...positions]
    copy.sort((a, b) => {
      const aMv = a.marketValueUsd
      const bMv = b.marketValueUsd
      if (aMv == null && bMv == null) return 0
      if (aMv == null) return 1
      if (bMv == null) return -1
      return Number(bMv) - Number(aMv)
    })
    return copy
  }, [positions])

  // distinct sector 목록 (datalist 옵션). 빈 값 제외 + 알파벳 정렬.
  const sectorSuggestions = useMemo(
    () =>
      Array.from(
        new Set(
          positions
            .map((p) => p.sector)
            .filter((s): s is string => typeof s === 'string' && s.trim().length > 0),
        ),
      ).sort(),
    [positions],
  )

  // 보유 종목이 있는데 ticker 가 미선택이면 첫 종목으로 자동 선택 (페이지 진입 직후 편의).
  useEffect(() => {
    if (!ticker && sortedPositions.length > 0) {
      const first = sortedPositions[0]
      setTicker(first.ticker)
      setSector(first.sector ?? '')
    }
  }, [ticker, sortedPositions])

  // ticker 변경 시 그 종목의 현재 sector 로 sector 입력을 갱신.
  const handleTickerChange = (next: string) => {
    setTicker(next)
    const match = positions.find((p) => p.ticker === next)
    setSector(match?.sector ?? '')
    setErrorMessage(null)
  }

  const handleSectorChange = (raw: string) => {
    const clipped = raw.length > SECTOR_MAX_LENGTH ? raw.slice(0, SECTOR_MAX_LENGTH) : raw
    setSector(clipped)
  }

  const mutation = useMutation({
    mutationFn: ({ t, s }: { t: string; s: string | null }) => updateSector(t, s),
    onSuccess: (data) => {
      const message = data.sector
        ? `${data.ticker} 의 분류가 '${data.sector}' 로 변경되었습니다`
        : `${data.ticker} 의 분류가 제거되었습니다`
      showToast(message, 'success')
      setErrorMessage(null)
      void queryClient.invalidateQueries({ queryKey: ['portfolio'] })
    },
    onError: (err: Error) => {
      setErrorMessage(err.message)
    },
  })

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (mutation.isPending || !ticker) return
    setErrorMessage(null)
    const trimmed = sector.trim()
    mutation.mutate({ t: ticker, s: trimmed.length > 0 ? trimmed : null })
  }

  const isPending = mutation.isPending
  const isEmpty = !isLoading && positions.length === 0

  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h2 className="text-xl font-medium">종목 분류 변경</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          보유 종목의 분류(sector)를 매수/매도 없이 직접 변경합니다.
          분류는 대시보드 트리맵 색상 그룹화와 정렬에 사용됩니다.
        </p>
      </div>

      {isEmpty ? (
        <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
          보유 종목이 없습니다. 매수 거래 후 다시 시도하세요.
        </div>
      ) : (
        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-slate-600 dark:text-slate-300">종목</span>
              <select
                value={ticker}
                onChange={(e) => handleTickerChange(e.target.value)}
                disabled={isLoading || isPending}
                className="rounded-md border border-slate-300 bg-white px-3 py-2 font-mono tabular-nums text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 disabled:text-slate-400 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
              >
                {isLoading && <option value="">로딩 중...</option>}
                {!isLoading && !ticker && <option value="">종목 선택</option>}
                {sortedPositions.map((p) => (
                  <option key={p.ticker} value={p.ticker}>
                    {p.ticker} — {p.sector ?? '분류 미지정'}
                  </option>
                ))}
              </select>
            </label>

            <label className="flex flex-col gap-1 text-sm">
              <span className="flex items-center justify-between text-slate-600 dark:text-slate-300">
                <span>분류 (최대 {SECTOR_MAX_LENGTH}자)</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">
                  {sector.length}/{SECTOR_MAX_LENGTH}
                </span>
              </span>
              <input
                type="text"
                list="sectors-page-suggestions"
                value={sector}
                onChange={(e) => handleSectorChange(e.target.value)}
                placeholder="예: Big Tech, 반도체, 고배당"
                autoComplete="off"
                maxLength={SECTOR_MAX_LENGTH}
                disabled={isPending || !ticker}
                className="rounded-md border border-slate-300 bg-white px-3 py-2 text-slate-900 focus:border-slate-500 focus:outline-none disabled:bg-slate-100 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:disabled:bg-slate-800"
              />
              <datalist id="sectors-page-suggestions">
                {sectorSuggestions.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
              <span className="text-xs text-slate-500 dark:text-slate-400">
                비워두면 분류가 제거됩니다.
              </span>
            </label>
          </div>

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
              disabled={isPending || !ticker}
              className="min-h-[44px] rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:bg-slate-400 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300 dark:disabled:bg-slate-600"
            >
              {isPending ? '저장 중...' : '저장'}
            </button>
          </div>
        </form>
      )}
    </section>
  )
}
