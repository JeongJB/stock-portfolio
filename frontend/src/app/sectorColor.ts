// sector 라벨 → 결정적인 HSL 색상.
// 같은 sector 이름은 항상 같은 hue 가 보장돼 종목별/sector별 토글 시 시각 흐름이 끊기지 않는다.
// FNV-1a 32-bit 단순 해시. 충돌 회피 알고리즘은 의도적으로 도입하지 않음 — 1인용 + 사용자가 sector 이름 살짝 바꿔 우회.

const FNV_OFFSET = 0x811c9dc5
const FNV_PRIME = 0x01000193

function hashFnv1a(str: string): number {
  let h = FNV_OFFSET
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    // Math.imul 로 32-bit 곱셈 wrap 보장.
    h = Math.imul(h, FNV_PRIME)
  }
  return h >>> 0
}

/**
 * 라벨 → HSL CSS 문자열. saturation/lightness 는 고정.
 * "현금" / "분류 미지정" 같은 sentinel 라벨도 같은 함수에 통과 — 별도 분기 없음.
 */
export function sectorHsl(label: string): string {
  const hue = hashFnv1a(label) % 360
  return `hsl(${hue}, 65%, 55%)`
}
