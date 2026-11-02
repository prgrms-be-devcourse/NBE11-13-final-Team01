package com.zipick.infrastructure.dto;

import java.time.Instant;
import java.util.List;

/**
 * 공급주택 주변 생활 인프라 응답.
 * 거리는 카카오가 돌려준 직선거리이며 실제 도보 경로가 아니다.
 */
public record HousingInfraResponse(
        Long housingId,
        InfraStatus status,
        Instant collectedAt,
        String distanceBasis,
        List<InfraCategoryResponse> categories
) {

    private static final String STRAIGHT_LINE = "STRAIGHT_LINE";

    public static HousingInfraResponse of(
            Long housingId,
            InfraStatus status,
            Instant collectedAt,
            List<InfraCategoryResponse> categories
    ) {
        return new HousingInfraResponse(housingId, status, collectedAt, STRAIGHT_LINE, categories);
    }

    /** 보여 줄 수집 결과가 없는 상태(PENDING·UNAVAILABLE)의 응답. */
    public static HousingInfraResponse empty(Long housingId, InfraStatus status) {
        return of(housingId, status, null, List.of());
    }
}
