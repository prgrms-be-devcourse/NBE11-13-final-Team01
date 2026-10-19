package com.zipick.infrastructure.repository;

import com.zipick.infrastructure.domain.HousingInfraPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HousingInfraPlaceRepository extends JpaRepository<HousingInfraPlace, Long> {

    List<HousingInfraPlace> findAllBySnapshotIdOrderByIdAsc(Long snapshotId);

    /** 재수집 시 기존 행을 통째로 지우고 다시 넣는다. upsert 로직이 필요 없어진다. */
    @Modifying
    @Query("delete from HousingInfraPlace p where p.snapshotId = :snapshotId")
    int deleteBySnapshotId(@Param("snapshotId") Long snapshotId);
}
