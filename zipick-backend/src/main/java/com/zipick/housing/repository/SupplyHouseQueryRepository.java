package com.zipick.housing.repository;

import com.zipick.housing.domain.SupplyHouse;
import com.zipick.housing.dto.HouseFilter;
import java.util.List;
import java.util.Map;

public interface SupplyHouseQueryRepository {

    /** 필터 조건에 맞는 공급주택을 공고와 함께 조회한다. */
    List<SupplyHouse> findByFilter(HouseFilter filter, int limit);

    long countByFilter(HouseFilter filter);

    /** 필터 칩 개수 표시용 */
    enum Dimension { STATUS, AGENCY, RENTAL_TYPE, HOUSING_TYPE }

    /** 필터에 맞는 집을 dimension 값별로 센다. 값이 없는(null) 집은 세지 않는다. */
    Map<String, Long> countBy(HouseFilter filter, Dimension dimension);
}
