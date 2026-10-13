package com.zipick.recommendation.dto;

import tools.jackson.core.type.TypeReference;
import com.zipick.global.util.JsonUtil;
import com.zipick.recommendation.domain.RecommendationProfile;
import java.util.Map;

public record RecommendationProfileResponse(
        Long id,
        String name,
        boolean active,
        int version,
        Map<String, Integer> weights,
        Map<String, String> rules,
        Map<String, Integer> infraWeights
) {
    public static RecommendationProfileResponse from(RecommendationProfile p) {
        return new RecommendationProfileResponse(p.getId(), p.getName(), p.isActive(), p.getVersion(),
                JsonUtil.readIntMap(p.getWeights()),
                JsonUtil.read(p.getRules(), new TypeReference<Map<String, String>>() {}, Map.of()),
                JsonUtil.readIntMap(p.getInfraWeights()));
    }
}
