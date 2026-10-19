package com.zipick.interest.repository;

import com.zipick.interest.domain.InterestNotice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestNoticeRepository extends JpaRepository<InterestNotice, Long> {
    List<InterestNotice> findAllByUserIdOrderByIdDesc(Long userId);
    Optional<InterestNotice> findByUserIdAndNoticeId(Long userId, Long noticeId);
    boolean existsByUserIdAndNoticeId(Long userId, Long noticeId);
    /** 알림 대상: 저장한 사용자 전체 (스케줄러용) */
    List<InterestNotice> findAllByNoticeIdIn(List<Long> noticeIds);
}
