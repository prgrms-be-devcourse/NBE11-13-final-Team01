package com.zipick.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * M1 회귀 방지의 핵심 테스트.
 *
 * <p>지도 조회가 같은 판정식을 쓰려고 조건 구성을 HouseFilterPredicates 로 옮겼다.
 * 옮기면서 조건이 하나라도 달라지면 추천 후보와 전체 목록 결과가 통째로 바뀐다.
 * 그래서 M1 **이전** 구현을 그대로 복사해 둔 {@link LegacyCandidateFilterFixture} 와
 * 새 구현이 만들어 내는 Predicate 가 완전히 같은지 필터 조합마다 비교한다.
 *
 * <p>DB 없이 돌아간다. QueryDSL Predicate 의 문자열 표현은 조건 종류·순서·상수까지 담으므로,
 * 두 문자열이 같으면 생성되는 JPQL 도 같다.
 */
class HouseFilterPredicatesEquivalenceTest {

    @DisplayName("M1 전후의 추천·목록 조회 조건이 완전히 같다")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("filters")
    void producesSamePredicateAsBeforeRefactoring(String name, HouseFilter filter) {
        String before = LegacyCandidateFilterFixture.build(filter).toString();
        String after = HouseFilterPredicates.all(filter).toString();

        assertThat(after).as("M1 리팩터링으로 조회 조건이 바뀌면 안 된다: %s", name).isEqualTo(before);
    }

    @DisplayName("공고 조건과 공급 조건을 합치면 전체 조건이 된다")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("filters")
    void allIsNoticeScopeAndSupplyScopeCombined(String name, HouseFilter filter) {
        String all = HouseFilterPredicates.all(filter).toString();

        // 분리한 두 묶음이 전체 조건을 빠짐없이 덮는지 본다(빈 묶음은 조건을 더하지 않는다).
        HouseFilterPredicates.noticeScope(filter).getValue();
        HouseFilterPredicates.supplyScope(filter).getValue();

        assertThat(all).as("%s", name).contains("housing.active = true", "housingSupply.active = true");
    }

    static List<Object[]> filters() {
        return List.of(
                new Object[]{"빈 필터", HouseFilter.builder().includeUnknown(true).build()},
                new Object[]{"기본값(openOnly)", HouseFilter.openOnly()},
                new Object[]{"includeUnknown=false", HouseFilter.builder().includeUnknown(false).build()},
                new Object[]{"상태만", HouseFilter.builder().statuses(Set.of(NoticeStatus.OPEN)).includeUnknown(true).build()},
                new Object[]{"상태 둘", HouseFilter.builder().statuses(HouseFilter.OPEN_STATUSES).includeUnknown(true).build()},
                new Object[]{"공고 id", HouseFilter.builder().noticeIds(Set.of(1L)).includeUnknown(true).build()},
                new Object[]{"기관", HouseFilter.builder().agencies(Set.of("LH")).includeUnknown(true).build()},
                new Object[]{"지역(시도+시군구)", HouseFilter.builder().regions(Set.of("서울특별시 강남구")).includeUnknown(true).build()},
                new Object[]{"지역(시도만)", HouseFilter.builder().regions(Set.of("서울특별시")).includeUnknown(true).build()},
                new Object[]{"임대유형 하나", HouseFilter.builder().rentalTypes(Set.of(RentalType.HAPPY)).includeUnknown(true).build()},
                new Object[]{"임대유형 청년/일반 매입", HouseFilter.builder()
                        .rentalTypes(Set.of(RentalType.YOUTH_PURCHASE, RentalType.PURCHASE)).includeUnknown(true).build()},
                new Object[]{"임대유형 PUBLIC", HouseFilter.builder().rentalTypes(Set.of(RentalType.PUBLIC)).includeUnknown(true).build()},
                new Object[]{"주택유형(unknown 포함)", HouseFilter.builder()
                        .housingTypes(Set.of(HousingType.APARTMENT)).includeUnknown(true).build()},
                new Object[]{"주택유형(unknown 제외)", HouseFilter.builder()
                        .housingTypes(Set.of(HousingType.APARTMENT)).includeUnknown(false).build()},
                new Object[]{"보증금 범위(unknown 포함)", HouseFilter.builder()
                        .depositMin(1000).depositMax(3000).includeUnknown(true).build()},
                new Object[]{"보증금 범위(unknown 제외)", HouseFilter.builder()
                        .depositMin(1000).depositMax(3000).includeUnknown(false).build()},
                new Object[]{"월세 범위", HouseFilter.builder().rentMin(10).rentMax(50).includeUnknown(true).build()},
                new Object[]{"면적 범위", HouseFilter.builder()
                        .areaMin(new BigDecimal("20")).areaMax(new BigDecimal("60")).includeUnknown(true).build()},
                new Object[]{"전체 조합(unknown 포함)", full(true)},
                new Object[]{"전체 조합(unknown 제외)", full(false)}
        );
    }

    private static HouseFilter full(boolean includeUnknown) {
        return HouseFilter.builder()
                .noticeIds(Set.of(7L, 9L))
                .statuses(HouseFilter.OPEN_STATUSES)
                .agencies(Set.of("LH", "SH"))
                .rentalTypes(Set.of(RentalType.HAPPY, RentalType.NATIONAL))
                .regions(Set.of("서울특별시 마포구"))
                .housingTypes(Set.of(HousingType.APARTMENT, HousingType.OFFICETEL))
                .depositMin(500).depositMax(5000)
                .rentMin(5).rentMax(80)
                .areaMin(new BigDecimal("18.5")).areaMax(new BigDecimal("59.9"))
                .includeUnknown(includeUnknown)
                .build();
    }
}
