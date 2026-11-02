package com.zipick.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.dto.HouseFilter;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지역 필터도 추천과 같은 계층 규칙을 쓰는지 본다.
 *
 * <p>주택 시군구에 일반구 정밀도가 생긴 뒤, 필터만 완전 일치로 남으면
 * "경기도 부천시"를 고른 사용자에게 지도는 0건, 추천은 N건이 되어 화면끼리 어긋난다.
 *
 * <p>DB 없이 Predicate 문자열로 확인한다(=생성될 JPQL 이 같다).
 */
class HouseFilterPredicatesRegionHierarchyTest {

    @Test
    @DisplayName("시군구를 고르면 그 시의 일반구까지 포함하는 조건이 만들어진다")
    void includesGeneralGuOfSelectedCity() {
        String condition = HouseFilterPredicates
                .all(HouseFilter.builder().regions(Set.of("경기도 부천시")).includeUnknown(true).build())
                .toString();

        assertThat(condition).contains("housing.provinceName = 경기도");
        assertThat(condition).contains("housing.districtName = 부천시");
        // 일반구 포함: "부천시 " 로 시작하는 값도 통과한다
        assertThat(condition).contains("startsWith(housing.districtName,부천시 )");
    }

    @Test
    @DisplayName("시도만 고르면 예전처럼 시도 비교만 한다")
    void provinceOnlyKeyUnchanged() {
        String condition = HouseFilterPredicates
                .all(HouseFilter.builder().regions(Set.of("서울특별시")).includeUnknown(true).build())
                .toString();

        assertThat(condition).contains("housing.provinceName = 서울특별시");
        assertThat(condition).doesNotContain("startsWith");
    }

    @Test
    @DisplayName("일반구까지 고르면 형제 구가 섞이지 않는다")
    void selectingGeneralGuStaysNarrow() {
        String condition = HouseFilterPredicates
                .all(HouseFilter.builder().regions(Set.of("경기도 부천시 소사구")).includeUnknown(true).build())
                .toString();

        assertThat(condition).contains("housing.districtName = 부천시 소사구");
        // "부천시 소사구 " 로 시작하는 더 하위 값만 추가로 허용된다(실제로는 없다). 형제 구는 포함되지 않는다.
        assertThat(condition).contains("startsWith(housing.districtName,부천시 소사구 )");
        assertThat(condition).doesNotContain("부천시 원미구");
    }
}
