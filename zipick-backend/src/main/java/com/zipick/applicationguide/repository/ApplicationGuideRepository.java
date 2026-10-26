package com.zipick.applicationguide.repository;

import com.zipick.applicationguide.domain.ApplicationGuide;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationGuideRepository extends JpaRepository<ApplicationGuide, Long> {

    Optional<ApplicationGuide> findByUserIdAndNoticeId(Long userId, Long noticeId);

    /** 하루 생성 건수. 무료 사용량을 지키기 위한 상한 확인에 쓴다 */
    long countByGeneratedAtAfter(LocalDateTime since);
}
