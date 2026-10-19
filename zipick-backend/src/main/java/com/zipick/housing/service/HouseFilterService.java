package com.zipick.housing.service;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.dto.FilterOptionsResponse;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.dto.HouseListResponse;
import com.zipick.housing.dto.HouseSummaryResponse;
import com.zipick.housing.repository.CandidateQueryRepository;
import com.zipick.housing.repository.CandidateQueryRepository.Dimension;
import com.zipick.notice.domain.NoticeStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기본 필터링: 점수 없이 조건만으로 공급주택을 거른다 ("전체 공급주택" 모드).
 * 추천 목록도 같은 HouseFilter 로 후보를 거르므로 두 모드의 필터 결과가 일치한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HouseFilterService {

    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 2000;

    private final CandidateQueryRepository repository;

    /** 주택 단위로 묶어 돌려준다. 대표 호실은 같은 주택의 첫 호실 */
    public HouseListResponse search(HouseFilter filter, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        Map<Long, List<Candidate>> byHousing = new LinkedHashMap<>();
        for (Candidate c : repository.find(filter, null, MAX_LIMIT * 5)) {
            byHousing.computeIfAbsent(c.housingId(), k -> new ArrayList<>()).add(c);
        }
        List<HouseSummaryResponse> houses = byHousing.values().stream()
                .limit(size)
                .map(list -> HouseSummaryResponse.from(list.get(0), list.size()))
                .toList();
        return new HouseListResponse(repository.countHousings(filter), houses);
    }

    public FilterOptionsResponse options() {
        HouseFilter all = HouseFilter.builder()
                .statuses(Set.of(NoticeStatus.values()))
                .includeUnknown(true)
                .build();
        HouseFilter open = HouseFilter.openOnly();
        Map<String, String> names = repository.agencyNames();
        List<FilterOptionsResponse.AgencyOption> agencies = repository.countBy(open, Dimension.AGENCY).entrySet().stream()
                .map(e -> new FilterOptionsResponse.AgencyOption(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .toList();
        List<FilterOptionsResponse.RegionOption> regions = repository.countBy(open, Dimension.REGION).entrySet().stream()
                .map(e -> {
                    String key = e.getKey();
                    int i = key.indexOf(' ');
                    return new FilterOptionsResponse.RegionOption(key, i < 0 ? key : key.substring(0, i),
                            i < 0 ? null : key.substring(i + 1), e.getValue());
                })
                .toList();
        return new FilterOptionsResponse(
                repository.countBy(all, Dimension.STATUS),
                agencies,
                repository.countBy(open, Dimension.RENTAL_TYPE),
                repository.countBy(open, Dimension.HOUSING_TYPE),
                regions);
    }
}
