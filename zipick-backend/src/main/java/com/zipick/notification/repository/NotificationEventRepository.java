package com.zipick.notification.repository;

import com.zipick.notification.domain.NotificationEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {
    boolean existsByDedupeKey(String dedupeKey);
    List<NotificationEvent> findTop100ByStatusAndScheduledForLessThanEqualOrderByIdAsc(
            NotificationEvent.Status status, LocalDateTime now);
    List<NotificationEvent> findTop50ByUserIdOrderByIdDesc(Long userId);
}
