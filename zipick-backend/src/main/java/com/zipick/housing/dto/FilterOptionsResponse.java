package com.zipick.housing.dto;

import java.util.Map;

/**
 * 필터 칩에 표시할 값별 공급주택 수. 0건인 값은 화면에서 비활성으로 보여 준다.
 * 수집 기관이 늘어나면 코드 수정 없이 칩이 켜진다.
 *
 * statuses 는 모든 상태 기준, 나머지는 모집 중(공고중·접수중)인 집 기준이다.
 */
public record FilterOptionsResponse(
        Map<String, Long> statuses,
        Map<String, Long> agencies,
        Map<String, Long> rentalTypes,
        Map<String, Long> housingTypes
) {}
