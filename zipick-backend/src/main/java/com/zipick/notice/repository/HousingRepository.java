package com.zipick.notice.repository;

import com.zipick.notice.domain.Housing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HousingRepository extends JpaRepository<Housing, Long> {

    Optional<Housing> findByNoticeIdAndSourceHousingKey(Long noticeId, String sourceHousingKey);

    List<Housing> findAllByNoticeId(Long noticeId);
}
