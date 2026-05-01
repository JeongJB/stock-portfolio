import { useCallback, useEffect, useState } from 'react'

// 표준 TS 타입에 BeforeInstallPromptEvent 가 없어 자체 정의.
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export interface UseInstallPromptResult {
  canInstall: boolean
  promptInstall: () => Promise<'accepted' | 'dismissed' | 'unavailable'>
}

function isAlreadyInstalled(): boolean {
  if (typeof window === 'undefined') return false
  if (window.matchMedia?.('(display-mode: standalone)').matches) return true
  // iOS Safari 의 standalone 플래그(타입엔 없음).
  const nav = window.navigator as Navigator & { standalone?: boolean }
  return nav.standalone === true
}

export function useInstallPrompt(): UseInstallPromptResult {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null)
  const [installed, setInstalled] = useState<boolean>(() => isAlreadyInstalled())

  useEffect(() => {
    if (installed) return

    const onBeforeInstallPrompt = (e: Event) => {
      e.preventDefault()
      setDeferred(e as BeforeInstallPromptEvent)
    }
    const onAppInstalled = () => {
      setDeferred(null)
      setInstalled(true)
    }

    window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt)
    window.addEventListener('appinstalled', onAppInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstallPrompt)
      window.removeEventListener('appinstalled', onAppInstalled)
    }
  }, [installed])

  const promptInstall = useCallback(async (): Promise<
    'accepted' | 'dismissed' | 'unavailable'
  > => {
    if (!deferred) return 'unavailable'
    try {
      await deferred.prompt()
      const choice = await deferred.userChoice
      return choice.outcome
    } finally {
      // beforeinstallprompt 이벤트는 1회용이므로 결과와 무관하게 클리어.
      setDeferred(null)
    }
  }, [deferred])

  return {
    canInstall: !installed && deferred !== null,
    promptInstall,
  }
}
