package com.zipick.housing.dto;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HouseFilterAndDomainTest {

    @Test
    void parsesCommaSeparatedFilterParametersAndDefaultsStatuses() {
        HouseFilterParams params = new HouseFilterParams();
        params.setRegions(List.of("서울특별시 강남구, 경기도 성남시", " "));
        params.setHousingTypes(List.of("apartment,multi_household"));
        params.setAgencies(List.of("LH,SH"));
        params.setRentalTypes(List.of("happy,national"));
        params.setDepositMin(100);
        params.setAreaMax(new BigDecimal("84.9"));
        params.setIncludeUnknown(null);

        HouseFilter filter = params.toFilter();

        assertThat(filter.regions()).containsExactlyInAnyOrder("서울특별시 강남구", "경기도 성남시");
        assertThat(filter.housingTypes()).containsExactlyInAnyOrder(
                HousingType.APARTMENT, HousingType.MULTI_HOUSEHOLD);
        assertThat(filter.statuses()).isEqualTo(HouseFilter.OPEN_STATUSES);
        assertThat(filter.agencies()).containsExactlyInAnyOrder("LH", "SH");
        assertThat(filter.rentalTypes()).containsExactlyInAnyOrder(RentalType.HAPPY, RentalType.NATIONAL);
        assertThat(filter.includeUnknown()).isTrue();
    }

    @Test
    void usesExplicitStatusesAndHandlesEmptyInput() {
        HouseFilterParams params = new HouseFilterParams();
        params.setStatuses(List.of("closed"));
        params.setIncludeUnknown(false);

        assertThat(params.toFilter().statuses()).containsExactly(NoticeStatus.CLOSED);
        assertThat(params.toFilter().includeUnknown()).isFalse();
        assertThat(HouseFilter.parseStrings(null)).isEmpty();
        assertThat(HouseFilter.parseStrings(List.of())).isEmpty();
    }

    @Test
    void classifiesHousingAndRentalTypesAndCandidateHelpers() {
        assertThat(HousingType.classify(" 아파트 ")).isEqualTo(HousingType.APARTMENT);
        assertThat(HousingType.classify("다가구")).isEqualTo(HousingType.MULTI_HOUSEHOLD);
        assertThat(HousingType.classify("알수없음")).isNull();
        assertThat(HousingType.classify(" ")).isNull();

        assertThat(RentalType.classify("", "통합공공임대 모집")).isEqualTo(RentalType.INTEGRATED);
        assertThat(RentalType.classify(null, "청년 매입임대")).isEqualTo(RentalType.YOUTH_PURCHASE);
        assertThat(RentalType.classify(null, "신혼 매입임대")).isEqualTo(RentalType.PURCHASE);
        assertThat(RentalType.classify(null, "10년임대")).isEqualTo(RentalType.PUBLIC);
        assertThat(RentalType.classify(null, "알 수 없는 유형")).isNull();
        assertThat(RentalType.classify(null, null)).isNull();
        for (RentalType type : RentalType.values()) {
            assertThat(type.getLabel()).isNotBlank();
        }

        assertThat(Candidate.regionKey(null, null)).isNull();
        assertThat(Candidate.regionKey(null, "강남구")).isEqualTo("강남구");
        assertThat(Candidate.regionKey("서울특별시", null)).isEqualTo("서울특별시");
        assertThat(Candidate.regionKey("서울특별시", "강남구")).isEqualTo("서울특별시 강남구");
        assertThat(Candidate.toManwon(null)).isNull();
        assertThat(Candidate.toManwon(15_000L)).isEqualTo(2);

        Candidate candidate = new Candidate(
                1L, 2L, 3L, "공고", "행복주택", RentalType.HAPPY, NoticeStatus.OPEN,
                null, null, "LH", "LH", "단지", "주소", "서울특별시", "강남구",
                null, null, null, "아파트", HousingType.APARTMENT, null,
                null, null, null, null, null
        );
        assertThat(candidate.regionKey()).isEqualTo("서울특별시 강남구");
    }
}
