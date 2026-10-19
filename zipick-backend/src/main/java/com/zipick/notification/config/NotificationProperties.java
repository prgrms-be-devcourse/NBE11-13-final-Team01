package com.zipick.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param pollInterval 아웃박스를 읽는 주기
 * @param maxAttempts  실패 시 재시도 횟수
 * @param schedulerEnabled false 면 스케줄러를 띄우지 않는다 (테스트·배치 실행용)
 */
@ConfigurationProperties(prefix = "zipick.notification")
public record NotificationProperties(Duration pollInterval, int maxAttempts, boolean schedulerEnabled) {
    public NotificationProperties {
        if (pollInterval == null) pollInterval = Duration.ofMinutes(5);
        if (maxAttempts <= 0) maxAttempts = 3;
    }
}
