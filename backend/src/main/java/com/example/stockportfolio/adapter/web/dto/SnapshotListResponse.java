package com.example.stockportfolio.adapter.web.dto;

import java.util.List;

/**
 * GET /api/snapshots 응답. 추후 메타(예: 다음 페이지 커서)를 추가할 여지를 위해 wrapper로 둔다.
 */
public record SnapshotListResponse(List<SnapshotView> snapshots) {
}
