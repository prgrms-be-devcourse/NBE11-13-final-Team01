package com.zipick.notice.dto;

import java.util.List;

public record MapHousingListResponse(int total, List<MapHousingSummaryResponse> housings) {
}
