package com.zipick.notification.repository;

import com.zipick.notification.domain.NotificationSetting;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {
    /** 정보성 알림(마감·새 공고)이 켜진 사용자 */
    List<NotificationSetting> findAllByEnabledTrue();
    /** 광고성 정보 수신에 동의한 사용자 (캠페인 대상) */
    List<NotificationSetting> findAllByEnabledTrueAndMarketingConsentTrue();
}
