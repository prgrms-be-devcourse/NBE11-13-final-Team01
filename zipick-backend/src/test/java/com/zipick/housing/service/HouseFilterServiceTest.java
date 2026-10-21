package com.zipick.housing.service;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.repository.CandidateQueryRepository;
import com.zipick.notice.domain.NoticeStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HouseFilterServiceTest {

    private final CandidateQueryRepository repository = mock(CandidateQueryRepository.class);
    private final HouseFilterService service = new HouseFilterService(repository);

    @Test
    void groupsSuppliesByHousingAndAppliesRequestedLimit() {
        HouseFilter filter = HouseFilter.openOnly();
        when(repository.find(eq(filter), eq(null), eq(HouseFilterService.MAX_LIMIT * 5)))
                .thenReturn(List.of(candidate(1L, 11L), candidate(2L, 11L), candidate(3L, 22L)));
        when(repository.countHousings(filter)).thenReturn(2L);

        var result = service.search(filter, 1);

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.houses()).hasSize(1);
        assertThat(result.houses().getFirst().supplyUnits()).isEqualTo(2);
    }

    @Test
    void clampsLimitsAndBuildsFilterOptions() {
        HouseFilter filter = HouseFilter.openOnly();
        when(repository.find(any(), eq(null), eq(HouseFilterService.MAX_LIMIT * 5))).thenReturn(List.of());
        service.search(filter, 0);
        service.search(filter, HouseFilterService.MAX_LIMIT + 1);
        service.search(filter, null);

        Map<String, String> names = Map.of("LH", "한국토지주택공사");
        when(repository.agencyNames()).thenReturn(names);
        when(repository.countBy(any(), eq(CandidateQueryRepository.Dimension.STATUS)))
                .thenReturn(Map.of("OPEN", 3L));
        when(repository.countBy(any(), eq(CandidateQueryRepository.Dimension.AGENCY)))
                .thenReturn(Map.of("LH", 2L, "UNKNOWN", 1L));
        when(repository.countBy(any(), eq(CandidateQueryRepository.Dimension.RENTAL_TYPE)))
                .thenReturn(Map.of("HAPPY", 2L));
        when(repository.countBy(any(), eq(CandidateQueryRepository.Dimension.HOUSING_TYPE)))
                .thenReturn(Map.of("APARTMENT", 2L));
        Map<String, Long> regions = new LinkedHashMap<>();
        regions.put("서울특별시 강남구", 2L);
        regions.put("세종특별자치시", 1L);
        when(repository.countBy(any(), eq(CandidateQueryRepository.Dimension.REGION))).thenReturn(regions);

        var options = service.options();

        assertThat(options.agencies()).extracting("name")
                .containsExactlyInAnyOrder("한국토지주택공사", "UNKNOWN");
        assertThat(options.regions()).hasSize(2);
    }

    private Candidate candidate(Long supplyId, Long housingId) {
        return new Candidate(
                supplyId, housingId, 100L, "행복주택 모집", "행복주택", RentalType.HAPPY,
                NoticeStatus.OPEN, LocalDate.of(2026, 10, 30), "https://example.com",
                "LH", "한국토지주택공사", "단지", "서울 강남구", "서울특별시", "강남구",
                new BigDecimal("37.5"), new BigDecimal("127.0"), 2020,
                "아파트", HousingType.APARTMENT, new BigDecimal("39.5"), 2, 3, 1000, 30, 1
        );
    }
}
