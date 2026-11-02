package com.zipick.housing.domain;

import java.util.Map;

/**
 * 관심 지역 ↔ 주택 지역 비교. **순수 로직이다** (DB·Spring 의존 없음).
 *
 * <p>지역 키는 {@link Candidate#regionKey()} 형식인 {@code "시도 시군구"}이고,
 * 일반구가 있는 도시는 시군구 자리에 {@code "천안시 서북구"}처럼 두 토큰이 들어간다.
 *
 * <p><b>상위 행정구역을 고르면 하위를 포함한다.</b>
 * <ul>
 *   <li>관심 {@code 경기도 부천시} → 주택 {@code 경기도 부천시 소사구} <b>포함</b>
 *   <li>관심 {@code 경기도 부천시 소사구} → 주택 {@code 경기도 부천시 원미구} <b>불포함</b>
 *   <li>시도가 다르면 언제나 불포함
 * </ul>
 *
 * <p>이 규칙이 없으면 주택 지역에 일반구 정밀도가 생긴 순간
 * {@code 경기도 부천시}를 고른 사용자의 관심 지역이 통째로 매칭에서 빠진다.
 */
public final class RegionMatcher {

    private RegionMatcher() {
    }

    /** 관심 지역 키가 주택 지역 키를 포함하는가 (같거나 상위 행정구역인가) */
    public static boolean covers(String preferredKey, String housingKey) {
        if (preferredKey == null || housingKey == null) return false;
        if (preferredKey.equals(housingKey)) return true;

        String preferredProvince = province(preferredKey);
        String housingProvince = province(housingKey);
        if (!preferredProvince.equals(housingProvince)) return false;

        String preferredDistrict = district(preferredKey);
        if (preferredDistrict == null) return true;   // 관심 지역이 시도 전체

        String housingDistrict = district(housingKey);
        if (housingDistrict == null) return false;    // 주택은 시도까지만 — 하위로 볼 수 없다

        // 같은 시군구이거나, 그 시의 일반구다 ("부천시" ⊃ "부천시 소사구")
        return housingDistrict.equals(preferredDistrict)
                || housingDistrict.startsWith(preferredDistrict + " ");
    }

    /**
     * 주택 지역이 관심 지역 중 어디에 해당하는지의 순위. 없으면 null.
     * 여러 관심 지역이 걸리면 더 높은 순위(숫자가 작은 쪽)를 쓴다.
     */
    public static Integer rankOf(Map<String, Integer> regionRanks, String housingKey) {
        if (regionRanks == null || regionRanks.isEmpty() || housingKey == null) return null;

        Integer exact = regionRanks.get(housingKey);
        if (exact != null) return exact;

        Integer best = null;
        for (Map.Entry<String, Integer> entry : regionRanks.entrySet()) {
            if (!covers(entry.getKey(), housingKey)) continue;
            if (best == null || entry.getValue() < best) best = entry.getValue();
        }
        return best;
    }

    /** "시도 시군구..." 에서 시도. 시도 이름에는 공백이 없다 */
    private static String province(String key) {
        int i = key.indexOf(' ');
        return i < 0 ? key : key.substring(0, i);
    }

    /** 시도 뒤의 나머지(시군구 + 일반구). 없으면 null */
    private static String district(String key) {
        int i = key.indexOf(' ');
        return i < 0 ? null : key.substring(i + 1);
    }
}
