package com.zipick.recommendation.engine;

import tools.jackson.core.type.TypeReference;
import com.zipick.global.util.JsonUtil;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.recommendation.domain.RecommendationProfile;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.dto.UserPreferenceRequest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 점수 계산에 필요한 사용자 쪽 정보를 한 번 풀어 둔 것.
 * 집마다 JSON을 다시 파싱하지 않도록 추천 1회당 1개 만든다.
 */
public record UserContext(
        UserProfile profile,
        UserPreference preference,
        Map<String, Integer> regionRanks,      // sigunguCode -> rank(1~3)
        Set<HousingType> preferredHousingTypes,
        Set<RentalType> preferredRentalTypes,
        Map<String, Integer> weights,          // cost/region/area/infra/type -> 1~5
        Map<String, String> rules,             // item -> MUST | PREFER
        Map<String, Integer> infraWeights,     // 카카오 카테고리 -> 1~5
        LocalDate today
) {
    public static UserContext of(UserProfile profile, UserPreference pref, RecommendationProfile rp,
                                 Map<String, Integer> defaultWeights) {
        List<UserPreferenceRequest.Region> regions = JsonUtil.read(pref.getRegions(),
                new TypeReference<List<UserPreferenceRequest.Region>>() {}, List.of());
        Map<String, Integer> regionRanks = new HashMap<>();
        for (var r : regions) regionRanks.put(r.code(), r.rank());

        Set<HousingType> types = new HashSet<>(JsonUtil.read(pref.getHousingTypes(),
                new TypeReference<List<HousingType>>() {}, List.of()));
        Set<RentalType> rentalTypes = new HashSet<>(JsonUtil.read(pref.getRentalTypes(),
                new TypeReference<List<RentalType>>() {}, List.of()));

        Map<String, Integer> weights = new HashMap<>();
        defaultWeights.keySet().forEach(k -> weights.put(k, 3));
        if (rp != null) weights.putAll(JsonUtil.readIntMap(rp.getWeights()));

        Map<String, String> rules = rp == null ? Map.of()
                : JsonUtil.read(rp.getRules(), new TypeReference<Map<String, String>>() {}, Map.of());
        Map<String, Integer> infraWeights = rp == null ? Map.of() : JsonUtil.readIntMap(rp.getInfraWeights());

        return new UserContext(profile, pref, regionRanks, types, rentalTypes, weights, rules, infraWeights, LocalDate.now());
    }

    public boolean isMust(String item) {
        return "MUST".equalsIgnoreCase(rules.get(item));
    }
}
