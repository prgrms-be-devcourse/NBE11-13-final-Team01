package com.zipick.housing.repository;

import com.zipick.housing.domain.SupplyHouse;
import com.zipick.housing.dto.HouseFilter;
import java.util.List;

public interface SupplyHouseQueryRepository {

    /** 필터 조건에 맞는 공급주택을 공고와 함께 조회한다. */
    List<SupplyHouse> findByFilter(HouseFilter filter, int limit);
}
