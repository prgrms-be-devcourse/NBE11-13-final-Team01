package com.zipick.housing.dto;

import java.util.List;
import java.util.Map;

/**
 * 필터 칩과 관심 지역 선택지. 값마다 주택 수를 함께 준다. 0건인 값은 화면에서 비활성으로 보여 준다.
 * 수집 데이터가 늘어나면(기관·지역) 코드 수정 없이 선택지가 생긴다.
 *
 * statuses 는 모든 상태 기준, 나머지는 모집 중(공고 예정·접수 중)인 주택 기준이다.
 */
public record FilterOptionsResponse(
        Map<String, Long> statuses,
        List<AgencyOption> agencies,
        Map<String, Long> rentalTypes,
        Map<String, Long> housingTypes,
        List<RegionOption> regions
) {
    public record AgencyOption(String code, String name, long count) {}

    /** key = "시도 시군구" (필터·관심 지역에 쓰는 값) */
    public record RegionOption(String key, String province, String district, long count) {}
}
