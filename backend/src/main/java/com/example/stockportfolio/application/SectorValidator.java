package com.example.stockportfolio.application;

/**
 * sector 자유 입력 라벨의 정규화·검증 정책. BUY 거래(RecordTradeCommand) 와
 * sector 변경 PATCH(PortfolioController) 두 진입점이 동일 정책을 공유한다.
 *
 * <p>정규화: trim → blank 면 null. 길이 검증: 정규화 후 String.length() 가
 * {@link #SECTOR_MAX_LENGTH} 를 초과하면 {@link IllegalArgumentException}.
 * 422/400 매핑은 호출자 책임.
 */
public final class SectorValidator {

    public static final int SECTOR_MAX_LENGTH = 30;

    private SectorValidator() {}

    /**
     * raw 입력을 정규화해 반환한다.
     * <ul>
     *   <li>null → null</li>
     *   <li>trim 후 빈 문자열 → null (분류 제거 의도)</li>
     *   <li>trim 후 길이 ≤ 30 → trim 결과</li>
     *   <li>trim 후 길이 &gt; 30 → {@link IllegalArgumentException}</li>
     * </ul>
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > SECTOR_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "sector 는 최대 " + SECTOR_MAX_LENGTH + "자 (입력: " + trimmed.length() + "자)");
        }
        return trimmed;
    }
}
