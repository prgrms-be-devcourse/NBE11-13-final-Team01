package com.zipick.housing.repository;

import com.zipick.housing.domain.InfraSummary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InfraSummaryRepository extends JpaRepository<InfraSummary, Long> {

    Optional<InfraSummary> findBySupplyHouseId(Long supplyHouseId);

    @Query("select i from InfraSummary i where i.supplyHouse.id in :ids")
    List<InfraSummary> findAllBySupplyHouseIdIn(@Param("ids") Collection<Long> ids);
}
