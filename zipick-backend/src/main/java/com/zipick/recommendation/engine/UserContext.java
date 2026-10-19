package com.zipick.recommendation.engine;

import tools.jackson.core.type.TypeReference;
import com.zipick.global.util.JsonUtil;
import com.zipick.housing.domain.HousingType;
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
        Map<String, Integer> regionRanks,      // "시도 시군구" -> rank(1~3)
        Set<HousingType> preferredHousingTypes,
        Map<String, Integer> weights,          // 항목 -> 우선순위 1~5
        Map<String, String> rules,             // 항목 -> MUST | PREFER
        Map<String, Integer> infraWeights,     // 카카오 카테고리 -> 1~5
        LocalDate today
) {
    public static UserContext of(UserProfile profile, UserPreference pref, RecommendationProfile rp,
                                 Map<String, Integer> defaultWeights) {
        return of(profile, pref, rp, defaultWeights, List.of());
    }

    /**
     * @param defaultMustItems 사용자가 규칙을 정하지 않았을 때 '꼭'으로 볼 항목 (설정: default-must-items)
     */
    public static UserContext of(UserProfile profile, UserPreference pref, RecommendationProfile rp,
                                 Map<String, Integer> defaultWeights, List<String> defaultMustItems) {
        List<UserPreferenceRequest.Region> regions = JsonUtil.read(pref.getRegions(),
                new TypeReference<List<UserPreferenceRequest.Region>>() {}, List.of());
        Map<String, Integer> regionRanks = new HashMap<>();
        for (var r : regions) regionRanks.put(r.code(), r.rank());

        Set<HousingType> types = new HashSet<>(JsonUtil.read(pref.getHousingTypes(),
                new TypeReference<List<HousingType>>() {}, List.of()));

        Map<String, Integer> weights = new HashMap<>();
        defaultWeights.keySet().forEach(k -> weights.put(k, 3));
        if (rp != null) weights.putAll(JsonUtil.readIntMap(rp.getWeights()));

        Map<String, String> rules = new HashMap<>();
        if (defaultMustItems != null) defaultMustItems.forEach(i -> rules.put(i, "MUST"));
        if (rp != null) rules.putAll(JsonUtil.read(rp.getRules(), new TypeReference<Map<String, String>>() {}, Map.of()));
        Map<String, Integer> infraWeights = rp == null ? Map.of() : JsonUtil.readIntMap(rp.getInfraWeights());

        return new UserContext(profile, pref, regionRanks, types, weights, rules, infraWeights, LocalDate.now());
    }

    public boolean isMust(String item) {
        return "MUST".equalsIgnoreCase(rules.get(item));
    }

    public UserContext withPreference(UserPreference p) {
        return new UserContext(profile, p, regionRanks, preferredHousingTypes, weights, rules, infraWeights, today);
    }

    public UserContext withRules(Map<String, String> r) {
        return new UserContext(profile, preference, regionRanks, preferredHousingTypes, weights, r, infraWeights, today);
    }

    public UserContext withHousingTypes(Set<HousingType> t) {
        return new UserContext(profile, preference, regionRanks, t, weights, rules, infraWeights, today);
    }
}
