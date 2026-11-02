package com.zipick.recommendation.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 추천 기준 생성·수정 요청. 수정 시 null 인 필드는 바꾸지 않는다.
 *
 * weights:      {"cost":5,"region":4,"area":3,"infra":3,"type":2}  (1~5)
 * rules:        {"region":"MUST","cost":"PREFER"}
 * infraWeights: {"SW8":5,"CS2":4}  (1~5)
 */
public record RecommendationProfileRequest(
        @Size(min = 1, max = 30) String name,
        Map<String, Integer> weights,
        Map<String, String> rules,
        Map<String, Integer> infraWeights
) {}
