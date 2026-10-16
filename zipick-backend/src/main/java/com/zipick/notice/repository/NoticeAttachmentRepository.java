package com.zipick.notice.repository;

import com.zipick.notice.domain.NoticeAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface NoticeAttachmentRepository extends JpaRepository<NoticeAttachment, Long> {

    Optional<NoticeAttachment> findByNoticeIdAndSourceKey(Long noticeId, String sourceKey);

    List<NoticeAttachment> findAllByNoticeIdOrderByIdAsc(Long noticeId);
}
