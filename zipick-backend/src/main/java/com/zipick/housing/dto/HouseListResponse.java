package com.zipick.housing.dto;

import java.util.List;

/**
 * @param total  필터에 맞는 전체 개수
 * @param houses 마감 임박순 최대 limit 건
 */
public record HouseListResponse(long total, List<HouseSummaryResponse> houses) {}
