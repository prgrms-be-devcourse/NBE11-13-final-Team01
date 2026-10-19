package com.zipick.notice.repository;

import com.zipick.notice.domain.HousingSupplyCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HousingSupplyConditionRepository extends JpaRepository<HousingSupplyCondition, Long> {
    Optional<HousingSupplyCondition> findByHousingSupplyIdAndConditionCode(Long housingSupplyId, String conditionCode);
    List<HousingSupplyCondition> findAllByHousingSupplyId(Long housingSupplyId);
    List<HousingSupplyCondition> findAllByHousingSupplyIdIn(List<Long> housingSupplyIds);
}
