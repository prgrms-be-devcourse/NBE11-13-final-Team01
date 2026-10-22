package com.zipick.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 어떤 필터가 어느 테이블에서 판정되는지 고정한다.
 * 이름만 보고 짐작하면 틀리는 두 가지가 핵심이다.
 * - 공공임대 유형(rentalTypes)은 공고(notice)의 유형·제목으로 분류한다. 공급 정보가 아니다.
 * - 주택 유형(housingTypes)은 공급 호실(housing_supply.housingType)의 원본 문자열이다. 주택이 아니다.
 *
 * 분류가 틀리면 지도에서 EXISTS 안팎에 조건이 잘못 들어가 결과가 달라진다.
 */
class HouseFilterPredicatesScopeTest {

    @Test
    @DisplayName("모집 상태·지역·기관은 공고·주택 조건으로 분류된다")
    void noticeAndHousingConditionsGoToNoticeScope() {
        HouseFilter filter = HouseFilter.builder()
                .statuses(Set.of(NoticeStatus.OPEN))
                .regions(Set.of("서울특별시 마포구"))
                .agencies(Set.of("LH"))
                .includeUnknown(false)
                .build();

        String scope = HouseFilterPredicates.noticeScope(filter).toString();

        assertThat(scope).contains("notice.status", "housing.provinceName", "agency.code");
        assertThat(HouseFilterPredicates.supplyScope(filter).hasValue())
                .as("공급 조건을 고르지 않았으므로 공급 조건은 비어야 한다").isFalse();
    }

    @Test
    @DisplayName("공공임대 유형은 공고에서 판정한다 (공급 조건이 아니다)")
    void rentalTypeIsJudgedOnNotice() {
        HouseFilter filter = HouseFilter.builder()
                .rentalTypes(Set.of(RentalType.HAPPY))
                .includeUnknown(false)
                .build();

        assertThat(HouseFilterPredicates.noticeScope(filter).toString())
                .contains("notice.noticeType", "notice.title");
        assertThat(HouseFilterPredicates.supplyScope(filter).hasValue()).isFalse();
    }

    @Test
    @DisplayName("주택 유형은 공급 호실에서 판정한다 (주택이 아니다)")
    void housingTypeIsJudgedOnSupply() {
        HouseFilter filter = HouseFilter.builder()
                .housingTypes(Set.of(HousingType.APARTMENT))
                .includeUnknown(false)
                .build();

        assertThat(HouseFilterPredicates.supplyScope(filter).toString()).contains("housingSupply.housingType");
        assertThat(HouseFilterPredicates.noticeScope(filter).hasValue()).isFalse();
    }

    @Test
    @DisplayName("보증금·월세·면적은 공급 호실에서 판정한다")
    void moneyAndAreaGoToSupplyScope() {
        HouseFilter filter = HouseFilter.builder()
                .depositMax(3000).rentMax(50).areaMin(new BigDecimal("20"))
                .includeUnknown(false)
                .build();

        String scope = HouseFilterPredicates.supplyScope(filter).toString();

        assertThat(scope).contains("housingSupply.rentDeposit", "housingSupply.monthlyRent", "housingSupply.exclusiveArea");
        assertThat(scope).as("금액은 원 단위라 만 원을 곱한다").contains("30000000", "500000");
        assertThat(HouseFilterPredicates.noticeScope(filter).hasValue()).isFalse();
    }

    @Test
    @DisplayName("공급 조건은 공고·주택 컬럼을 참조하지 않는다 (EXISTS 안으로 들어가므로)")
    void supplyScopeNeverTouchesNoticeOrHousing() {
        HouseFilter filter = HouseFilter.builder()
                .statuses(Set.of(NoticeStatus.OPEN))
                .regions(Set.of("서울특별시 마포구"))
                .rentalTypes(Set.of(RentalType.HAPPY))
                .housingTypes(Set.of(HousingType.APARTMENT))
                .depositMax(3000).areaMin(new BigDecimal("20"))
                .includeUnknown(false)
                .build();

        assertThat(HouseFilterPredicates.supplyScope(filter).toString())
                .doesNotContain("notice.", "housing.province", "agency.");
        assertThat(HouseFilterPredicates.noticeScope(filter).toString())
                .doesNotContain("housingSupply.");
    }

    @Test
    @DisplayName("includeUnknown=false 면 값이 없는 호실은 조건을 만족하지 않는다")
    void strictModeDoesNotAcceptNullValues() {
        HouseFilter strict = HouseFilter.builder().depositMax(3000).includeUnknown(false).build();

        assertThat(HouseFilterPredicates.supplyScope(strict).toString()).doesNotContain("is null");
    }

    @Test
    @DisplayName("includeUnknown=true 면 값이 없는 호실도 통과한다 (전체 목록·추천의 기존 정책)")
    void lenientModeAcceptsNullValues() {
        HouseFilter lenient = HouseFilter.builder().depositMax(3000).includeUnknown(true).build();

        assertThat(HouseFilterPredicates.supplyScope(lenient).toString()).contains("is null");
    }
}
