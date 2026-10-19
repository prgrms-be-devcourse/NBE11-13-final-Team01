package com.zipick.notification.repository;

import com.zipick.notification.domain.NotificationCursor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationCursorRepository extends JpaRepository<NotificationCursor, String> {
}
