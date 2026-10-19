package com.zipick.infrastructure.dto;

/**
 * 카테고리 1개의 요약. 화면에 "지하철역: 신설동역 · 683m · 주변 6곳" 한 줄로 쓰인다.
 * totalCount 는 저장한 시설 수가 아니라 반경 내 검색 결과 전체 수다.
 */
public record InfraCategoryResponse(
        String code,
        String label,
        int radiusMeter,
        String nearestName,
        int nearestDistanceMeter,
        int totalCount
) {
}
