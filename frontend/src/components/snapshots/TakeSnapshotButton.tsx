import { useMutation, useQueryClient } from '@tanstack/react-query'
import { takeSnapshot } from '../../api/client'
import type { SnapshotView } from '../../api/types'
import { useToast } from '../../app/toastContext'

interface Props {
  // 현재 차트에 보이는 날짜 집합. 같은 KST 날짜 재호출 시 덮어쓰기로 처리되므로
  // 응답 date가 이 집합에 있으면 "갱신" 토스트로, 없으면 "저장" 토스트로 분기한다.
  existingDates: Set<string>
}

export function TakeSnapshotButton({ existingDates }: Props) {
  const queryClient = useQueryClient()
  const { showToast } = useToast()

  const mutation = useMutation({
    mutationFn: takeSnapshot,
    onSuccess: (snapshot: SnapshotView) => {
      const isUpdate = existingDates.has(snapshot.date)
      const message = isUpdate
        ? '오늘자 스냅샷이 갱신되었습니다'
        : `${snapshot.date} 스냅샷이 저장되었습니다`
      showToast(message, 'success')
      void queryClient.invalidateQueries({ queryKey: ['snapshots'] })
    },
    onError: (err: Error) => {
      showToast(`스냅샷 박제 실패: ${err.message}`, 'error')
    },
  })

  return (
    <button
      type="button"
      onClick={() => mutation.mutate()}
      disabled={mutation.isPending}
      className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:bg-slate-400 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300 dark:disabled:bg-slate-600"
    >
      {mutation.isPending ? '박제 중...' : '지금 박제'}
    </button>
  )
}
