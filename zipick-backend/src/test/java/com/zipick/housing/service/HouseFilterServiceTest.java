package com.zipick.housing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.dto.FilterOptionsResponse;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.dto.HouseListResponse;
import com.zipick.housing.repository.CandidateQueryRepository;
import com.zipick.housing.repository.CandidateQueryRepository.Dimension;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 전체 공급주택: 호실을 주택 단위로 묶고, 필터 선택지(기관·지역)를 이름과 개수로 만든다. */
@ExtendWith(MockitoExtension.class)
class HouseFilterServiceTest {

    @Mock CandidateQueryRepository repository;
    @InjectMocks HouseFilterService service;

    private static Candidate unit(long housingId) {
        Candidate c = mock(Candidate.class);
        when(c.housingId()).thenReturn(housingId);
        return c;
    }

    @Test
    void 호실을_주택_단위로_묶고_요청한_개수까지만_돌려준다() {
        HouseFilter filter = HouseFilter.openOnly();
        List<Candidate> units = List.of(unit(1), unit(1), unit(2), unit(3));   // 목은 when(...) 밖에서 만든다
        when(repository.find(eq(filter), isNull(), anyInt())).thenReturn(units);
        when(repository.countHousings(filter)).thenReturn(3L);

        HouseListResponse r = service.search(filter, 2);

        assertThat(r.total()).isEqualTo(3);
        assertThat(r.houses()).hasSize(2);
    }

    @Test
    void 개수_제한은_1에서_최대값_사이로_맞춘다() {
        HouseFilter filter = HouseFilter.openOnly();
        List<Candidate> units = List.of(unit(1), unit(2));
        when(repository.find(eq(filter), isNull(), anyInt())).thenReturn(units);
        when(repository.countHousings(filter)).thenReturn(2L);

        assertThat(service.search(filter, 0).houses()).hasSize(1);      // 0 → 1
        assertThat(service.search(filter, null).houses()).hasSize(2);   // 기본 500
        assertThat(service.search(filter, 99999).houses()).hasSize(2);  // 최대 2000
    }

    @Test
    void 필터_선택지는_기관_이름을_붙이고_지역을_시도와_시군구로_나눈다() {
        Map<String, Long> agencies = new LinkedHashMap<>();
        agencies.put("LH", 10L);
        agencies.put("UNKNOWN_CODE", 2L);
        Map<String, Long> regions = new LinkedHashMap<>();
        regions.put("경상북도 구미시", 5L);
        regions.put("세종특별자치시", 1L);
        when(repository.agencyNames()).thenReturn(Map.of("LH", "한국토지주택공사"));
        when(repository.countBy(any(), eq(Dimension.AGENCY))).thenReturn(agencies);
        when(repository.countBy(any(), eq(Dimension.REGION))).thenReturn(regions);
        when(repository.countBy(any(), eq(Dimension.STATUS))).thenReturn(Map.of("OPEN", 3L));
        when(repository.countBy(any(), eq(Dimension.RENTAL_TYPE))).thenReturn(Map.of());
        when(repository.countBy(any(), eq(Dimension.HOUSING_TYPE))).thenReturn(Map.of());

        FilterOptionsResponse r = service.options();

        assertThat(r.agencies()).extracting(FilterOptionsResponse.AgencyOption::name)
                .containsExactly("한국토지주택공사", "UNKNOWN_CODE");   // 이름이 없으면 코드 그대로
        assertThat(r.regions().get(0)).isEqualTo(new FilterOptionsResponse.RegionOption("경상북도 구미시", "경상북도", "구미시", 5));
        assertThat(r.regions().get(1)).isEqualTo(new FilterOptionsResponse.RegionOption("세종특별자치시", "세종특별자치시", null, 1));
        assertThat(r.statuses()).containsEntry("OPEN", 3L);
    }
}
