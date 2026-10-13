package com.zipick.notice.repository;

import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Long>, JpaSpecificationExecutor<Notice> {

    Optional<Notice> findBySourceAndExternalNoticeId(String source, String externalNoticeId);

    List<Notice> findAllBySource(String source);

    Page<Notice> findByStatusIn(Collection<NoticeStatus> statuses, Pageable pageable);
}
