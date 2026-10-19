package com.zipick.notification.repository;

import com.zipick.notification.domain.NotificationSetting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {
    Optional<NotificationSetting> findByUserId(Long userId);
    List<NotificationSetting> findAllByEnabledTrue();
}
