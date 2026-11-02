package com.zipick.housing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 쿼리 파라미터(쉼표 구분·대소문자·빈 값)를 필터로 바꾸는 규칙 */
class HouseFilterTest {

    @Test
    void 쉼표로_이어진_값과_반복된_파라미터를_모두_받는다() {
        assertThat(HouseFilter.parseStrings(List.of("경상북도 구미시, 경상북도 김천시", "서울특별시 마포구", " ")))
                .containsExactlyInAnyOrder("경상북도 구미시", "경상북도 김천시", "서울특별시 마포구");
        assertThat(HouseFilter.parseStrings(null)).isEmpty();
    }

    @Test
    void enum_은_대소문자를_가리지_않는다() {
        assertThat(HouseFilter.parseEnums(List.of("happy,national"), RentalType.class))
                .containsExactlyInAnyOrder(RentalType.HAPPY, RentalType.NATIONAL);
    }

    @Test
    void 모집_상태를_비우면_공고_예정과_접수_중() {
        HouseFilter f = new HouseFilterParams().toFilter();
        assertThat(f.statuses()).containsExactlyInAnyOrderElementsOf(HouseFilter.OPEN_STATUSES);
        assertThat(f.includeUnknown()).isTrue();
        assertThat(HouseFilter.openOnly().statuses()).doesNotContain(NoticeStatus.CLOSED);
    }

    @Test
    void 파라미터를_필터로_옮긴다() {
        HouseFilterParams p = new HouseFilterParams();
        p.setRegions(List.of("경상북도 구미시"));
        p.setStatuses(List.of("closed"));
        p.setHousingTypes(List.of("apartment"));
        p.setRentMax(40);
        p.setIncludeUnknown(false);

        HouseFilter f = p.toFilter();

        assertThat(f.regions()).containsExactly("경상북도 구미시");
        assertThat(f.statuses()).containsExactly(NoticeStatus.CLOSED);
        assertThat(f.housingTypes()).containsExactly(HousingType.APARTMENT);
        assertThat(f.rentMax()).isEqualTo(40);
        assertThat(f.includeUnknown()).isFalse();
        assertThat(Arrays.asList(NoticeStatus.values())).contains(NoticeStatus.UPCOMING);
    }
}
