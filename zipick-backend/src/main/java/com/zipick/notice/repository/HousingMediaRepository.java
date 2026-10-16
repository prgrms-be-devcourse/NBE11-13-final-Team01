package com.zipick.notice.repository;

import com.zipick.notice.domain.HousingMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HousingMediaRepository extends JpaRepository<HousingMedia, Long> {
    Optional<HousingMedia> findByHousingIdAndSourceKey(Long housingId, String sourceKey);
    List<HousingMedia> findAllByHousingIdOrderByIdAsc(Long housingId);
}
