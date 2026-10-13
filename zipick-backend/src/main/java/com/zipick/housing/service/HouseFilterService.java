package com.zipick.housing.service;

import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.dto.FilterOptionsResponse;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.dto.HouseListResponse;
import com.zipick.housing.dto.HouseSummaryResponse;
import com.zipick.housing.repository.SupplyHouseQueryRepository.Dimension;
import com.zipick.housing.repository.SupplyHouseRepository;
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

    private final SupplyHouseRepository houseRepository;

    public HouseListResponse search(HouseFilter filter, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return new HouseListResponse(
                houseRepository.countByFilter(filter),
                houseRepository.findByFilter(filter, size).stream().map(HouseSummaryResponse::from).toList());
    }

    public FilterOptionsResponse options() {
        HouseFilter all = HouseFilter.builder()
                .statuses(Set.of(AnnouncementStatus.values()))
                .includeUnknown(true)
                .build();
        HouseFilter open = HouseFilter.openOnly();
        return new FilterOptionsResponse(
                houseRepository.countBy(all, Dimension.STATUS),
                houseRepository.countBy(open, Dimension.AGENCY),
                houseRepository.countBy(open, Dimension.RENTAL_TYPE),
                houseRepository.countBy(open, Dimension.HOUSING_TYPE));
    }
}
