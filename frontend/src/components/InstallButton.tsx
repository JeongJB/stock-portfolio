import { useInstallPrompt } from '../app/useInstallPrompt'
import { useToast } from '../app/toastContext'

export function InstallButton() {
  const { promptInstall } = useInstallPrompt()
  const { showToast } = useToast()

  const handleClick = async () => {
    try {
      const outcome = await promptInstall()
      if (outcome === 'accepted') {
        showToast('앱이 설치되었습니다', 'success')
        return
      }
      if (outcome === 'unavailable') {
        // beforeinstallprompt 가 발생 안 한 환경(모바일 Chrome 의 자체 검사 보수적 등).
        // 사용자에게 브라우저 메뉴 사용을 안내.
        showToast('브라우저 메뉴에서 "앱 설치" 또는 "홈 화면에 추가" 를 선택해 주세요')
      }
      // dismissed 는 노이즈 방지 차원에서 토스트 생략.
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      showToast(`설치 실패: ${message}`, 'error')
    }
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      className="inline-flex min-h-[36px] items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
      aria-label="앱 설치"
    >
      <InstallIcon />
      앱 설치
    </button>
  )
}

function InstallIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 20 20"
      fill="currentColor"
      className="h-3.5 w-3.5"
      aria-hidden="true"
    >
      <path d="M10.75 2.75a.75.75 0 0 0-1.5 0v8.69L6.03 8.22a.75.75 0 0 0-1.06 1.06l4.5 4.5a.75.75 0 0 0 1.06 0l4.5-4.5a.75.75 0 1 0-1.06-1.06l-3.22 3.22V2.75Z" />
      <path d="M3.5 13.75a.75.75 0 0 0-1.5 0v2.5A2.75 2.75 0 0 0 4.75 19h10.5A2.75 2.75 0 0 0 18 16.25v-2.5a.75.75 0 0 0-1.5 0v2.5c0 .69-.56 1.25-1.25 1.25H4.75c-.69 0-1.25-.56-1.25-1.25v-2.5Z" />
    </svg>
  )
}
