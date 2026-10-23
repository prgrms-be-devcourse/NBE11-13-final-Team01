package com.zipick.notice.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.dto.HouseFilterParams;
import com.zipick.notice.domain.NoticeStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공개 지도 조회 정책. 전체 목록·추천과 달라지는 두 가지(모집 상태 교집합, 엄격한 null 판정)를 고정한다.
 */
class MapHousingFilterPolicyTest {

    @Test
    @DisplayName("상태를 고르지 않으면 공고 예정 + 접수 중을 본다")
    void defaultsToUpcomingAndOpen() {
        assertThat(apply(null).statuses())
                .containsExactlyInAnyOrder(NoticeStatus.UPCOMING, NoticeStatus.OPEN);
    }

    @Test
    @DisplayName("허용 상태를 고르면 그 상태만 본다")
    void keepsRequestedVisibleStatus() {
        assertThat(apply(List.of("OPEN")).statuses()).containsExactly(NoticeStatus.OPEN);
        assertThat(apply(List.of("UPCOMING")).statuses()).containsExactly(NoticeStatus.UPCOMING);
    }

    @Test
    @DisplayName("마감 공고를 요청하면 빈 상태 집합이 된다 (조건을 빼면 전체가 나오므로 비워서 넘긴다)")
    void closedOnlyBecomesEmptySet() {
        HouseFilter filter = apply(List.of("CLOSED"));

        assertThat(filter.statuses()).isEmpty();
    }

    @Test
    @DisplayName("허용 상태와 마감 공고를 함께 요청하면 허용 상태만 남는다")
    void intersectsRequestedWithVisibleStatuses() {
        assertThat(apply(List.of("OPEN", "CLOSED")).statuses()).containsExactly(NoticeStatus.OPEN);
    }

    @Test
    @DisplayName("취소 공고도 노출되지 않는다")
    void dropsCancelled() {
        assertThat(apply(List.of("CANCELLED")).statuses()).isEmpty();
        assertThat(apply(List.of("UPCOMING", "CANCELLED")).statuses()).containsExactly(NoticeStatus.UPCOMING);
    }

    @Test
    @DisplayName("includeUnknown=true 로 요청해도 지도의 엄격한 판정을 우회할 수 없다")
    void alwaysStrictAboutMissingSupplyValues() {
        HouseFilterParams params = new HouseFilterParams();
        params.setIncludeUnknown(true);
        params.setDepositMax(3000);

        assertThat(MapHousingFilterPolicy.apply(params).includeUnknown()).isFalse();
    }

    @Test
    @DisplayName("상태 외의 필터 값은 그대로 넘어간다")
    void keepsOtherFilterValues() {
        HouseFilterParams params = new HouseFilterParams();
        params.setRegions(List.of("서울특별시 마포구"));
        params.setAgencies(List.of("LH"));
        params.setDepositMax(3000);
        params.setAreaMin(new java.math.BigDecimal("20"));

        HouseFilter filter = MapHousingFilterPolicy.apply(params);

        assertThat(filter.regions()).containsExactly("서울특별시 마포구");
        assertThat(filter.agencies()).containsExactly("LH");
        assertThat(filter.depositMax()).isEqualTo(3000);
        assertThat(filter.areaMin()).isEqualByComparingTo("20");
    }

    private HouseFilter apply(List<String> statuses) {
        HouseFilterParams params = new HouseFilterParams();
        params.setStatuses(statuses);
        return MapHousingFilterPolicy.apply(params);
    }
}
