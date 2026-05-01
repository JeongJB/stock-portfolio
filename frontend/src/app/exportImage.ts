import { toPng } from 'html-to-image'

// 대시보드 등 임의 DOM 요소를 PNG 로 캡처해 다운로드한다.
// pixelRatio 2 로 레티나 가독성 확보, 투명 배경 방지를 위해 흰색 배경 명시.
export async function exportElementAsPng(
  element: HTMLElement,
  filename: string,
): Promise<void> {
  const dataUrl = await toPng(element, {
    pixelRatio: 2,
    backgroundColor: '#ffffff',
    cacheBust: true,
  })
  const link = document.createElement('a')
  link.download = filename
  link.href = dataUrl
  link.click()
}
