package com.zipick.notice.repository;

import com.zipick.notice.domain.HousingSupply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HousingSupplyRepository extends JpaRepository<HousingSupply, Long> {

    Optional<HousingSupply> findByHousingIdAndSourceSupplyKey(Long housingId, String sourceSupplyKey);

    List<HousingSupply> findAllByHousingId(Long housingId);

    List<HousingSupply> findAllByHousingIdAndActiveTrueOrderByIdAsc(Long housingId);

    List<HousingSupply> findAllByHousingNoticeIdAndSourceSupplyKey(Long noticeId, String sourceSupplyKey);
}
