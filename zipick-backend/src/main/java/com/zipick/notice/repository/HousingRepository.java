package com.zipick.notice.repository;

import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.NoticeStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface HousingRepository extends JpaRepository<Housing, Long> {

    Optional<Housing> findByNoticeIdAndSourceHousingKey(Long noticeId, String sourceHousingKey);

    List<Housing> findAllByNoticeId(Long noticeId);

    @EntityGraph(attributePaths = {"notice", "notice.agency"})
    List<Housing> findAllByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullAndNoticeStatusOrderByIdAsc(
            NoticeStatus status
    );

    @EntityGraph(attributePaths = {"notice", "notice.agency"})
    Optional<Housing> findByIdAndActiveTrue(Long id);

    @Query("""
            select h
            from Housing h
            where h.active = true
              and (h.latitude is null or h.longitude is null)
            order by h.id
            """)
    List<Housing> findActiveWithoutCoordinates();
}
