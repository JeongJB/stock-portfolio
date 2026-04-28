import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { ToastContext, type ToastTone } from './toastContext'

interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

const DURATION_MS = 3500

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const seqRef = useRef(0)

  const showToast = useCallback((message: string, tone: ToastTone = 'success') => {
    seqRef.current += 1
    const id = seqRef.current
    setToasts((prev) => [...prev, { id, message, tone }])
    window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, DURATION_MS)
  }, [])

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <ToastViewport toasts={toasts} />
    </ToastContext.Provider>
  )
}

function ToastViewport({ toasts }: { toasts: ToastItem[] }) {
  if (toasts.length === 0) return null
  return (
    <div
      aria-live="polite"
      aria-atomic="true"
      className="pointer-events-none fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 flex-col items-center gap-2"
    >
      {toasts.map((t) => (
        <ToastItemView key={t.id} toast={t} />
      ))}
    </div>
  )
}

function ToastItemView({ toast }: { toast: ToastItem }) {
  // 진입 시 부드럽게 페이드인.
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    const handle = window.requestAnimationFrame(() => setVisible(true))
    return () => window.cancelAnimationFrame(handle)
  }, [])

  const tone =
    toast.tone === 'success'
      ? 'border-emerald-400 bg-emerald-50 text-emerald-800 dark:border-emerald-700 dark:bg-emerald-950/70 dark:text-emerald-200'
      : 'border-rose-400 bg-rose-50 text-rose-800 dark:border-rose-700 dark:bg-rose-950/70 dark:text-rose-200'

  return (
    <div
      role="status"
      className={`pointer-events-auto rounded-md border px-4 py-2 text-sm shadow-md transition-opacity duration-200 ${tone} ${
        visible ? 'opacity-100' : 'opacity-0'
      }`}
    >
      {toast.message}
    </div>
  )
}
