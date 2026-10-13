package com.zipick.recommendation.dto;

import java.util.Map;

/**
 * @param weights          비교한 항목끼리의 AHP 가중치 (합 1)
 * @param priorities       반영될 우선순위 1~5 (비교하지 않은 항목은 현재 값 유지)
 * @param consistencyRatio 일관성 비율 CR. 0.1 이하면 답이 서로 맞는다
 * @param consistent       CR ≤ 0.1
 * @param profile          apply=true 일 때 반영된 추천 기준, 아니면 null
 */
public record AhpResponse(
        Map<String, Double> weights,
        Map<String, Integer> priorities,
        double consistencyRatio,
        boolean consistent,
        RecommendationProfileResponse profile
) {}
