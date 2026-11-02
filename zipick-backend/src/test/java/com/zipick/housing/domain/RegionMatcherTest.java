package com.zipick.housing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 관심 지역 ↔ 주택 지역 계층 비교.
 *
 * <p>행정구역 정규화로 주택 시군구에 일반구 정밀도가 생기면서 필요해졌다.
 * 이 규칙이 없으면 "경기도 부천시"를 고른 사용자의 관심 지역이 통째로 빠진다.
 */
class RegionMatcherTest {

    @DisplayName("상위 행정구역을 고르면 하위를 포함한다")
    @ParameterizedTest(name = "관심 [{0}] vs 주택 [{1}] → {2}")
    @CsvSource({
            // 같은 값
            "경기도 부천시,            경기도 부천시,            true",
            "서울특별시 강남구,        서울특별시 강남구,        true",
            // 상위(시) → 하위(일반구)
            "경기도 부천시,            경기도 부천시 소사구,      true",
            "경기도 부천시,            경기도 부천시 원미구,      true",
            "충청남도 천안시,          충청남도 천안시 서북구,    true",
            // 하위를 고르면 형제 구는 포함하지 않는다
            "경기도 부천시 소사구,      경기도 부천시 원미구,      false",
            "경기도 부천시 소사구,      경기도 부천시,            false",
            // 시도만 고르면 그 안을 모두 포함한다
            "경기도,                   경기도 부천시,            true",
            "경기도,                   경기도 부천시 소사구,      true",
            // 시도가 다르면 언제나 아니다
            "경기도 부천시,            서울특별시 부천시,        false",
            "서울특별시 강남구,        경기도 강남구,            false",
            "경기도,                   서울특별시 강남구,        false",
            // 이름이 겹치는 다른 시군구 (접두사만 같다고 포함하면 안 된다)
            "경기도 광주시,            경기도 광주시 남구,        true",
            "경기도 성남시,            경기도 성남,              false",
    })
    void covers(String preferred, String housing, boolean expected) {
        assertThat(RegionMatcher.covers(preferred.trim(), housing.trim())).isEqualTo(expected);
    }

    @Test
    @DisplayName("null 은 매칭되지 않는다")
    void handlesNull() {
        assertThat(RegionMatcher.covers(null, "경기도 부천시")).isFalse();
        assertThat(RegionMatcher.covers("경기도 부천시", null)).isFalse();
        assertThat(RegionMatcher.rankOf(Map.of("경기도 부천시", 1), null)).isNull();
        assertThat(RegionMatcher.rankOf(Map.of(), "경기도 부천시")).isNull();
        assertThat(RegionMatcher.rankOf(null, "경기도 부천시")).isNull();
    }

    @Test
    @DisplayName("일반구 주택이 상위 관심 지역의 순위를 물려받는다")
    void rankOfInheritsFromParent() {
        Map<String, Integer> ranks = ranks("경기도 부천시", 1, "서울특별시 강남구", 2);

        assertThat(RegionMatcher.rankOf(ranks, "경기도 부천시 소사구")).isEqualTo(1);
        assertThat(RegionMatcher.rankOf(ranks, "경기도 부천시 원미구")).isEqualTo(1);
        assertThat(RegionMatcher.rankOf(ranks, "서울특별시 강남구")).isEqualTo(2);
        assertThat(RegionMatcher.rankOf(ranks, "강원특별자치도 춘천시")).isNull();
    }

    @Test
    @DisplayName("정확히 일치하는 관심 지역이 있으면 그 순위를 쓴다")
    void exactMatchWins() {
        Map<String, Integer> ranks = ranks("경기도 부천시", 3, "경기도 부천시 소사구", 1);

        assertThat(RegionMatcher.rankOf(ranks, "경기도 부천시 소사구")).isEqualTo(1);
        assertThat(RegionMatcher.rankOf(ranks, "경기도 부천시 원미구")).isEqualTo(3);
    }

    @Test
    @DisplayName("여러 관심 지역이 걸리면 더 높은 순위를 쓴다")
    void picksBestRank() {
        Map<String, Integer> ranks = ranks("경기도", 2, "경기도 부천시", 1);

        assertThat(RegionMatcher.rankOf(ranks, "경기도 부천시 소사구")).isEqualTo(1);
        assertThat(RegionMatcher.rankOf(ranks, "경기도 수원시 영통구")).isEqualTo(2);
    }

    private static Map<String, Integer> ranks(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (Integer) pairs[i + 1]);
        return out;
    }
}
