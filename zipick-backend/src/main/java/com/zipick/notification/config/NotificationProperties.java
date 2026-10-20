package com.zipick.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알림센터(zipick-notification) 연결 설정.
 * @param baseUrl      알림센터 주소 (같은 EC2 의 컨테이너: http://notification:8081)
 * @param internalKey  X-Internal-Key 로 보내는 공유 키
 * @param pollInterval 마감·새 공고를 찾아 알림센터에 적는 주기
 * @param schedulerEnabled false 면 스케줄러를 띄우지 않는다 (테스트·배치 실행용)
 * @param queueEnabled true 면 알림 요청을 RabbitMQ 로 던진다 (spring.rabbitmq.* 연결 설정 필요). false 면 HTTP 로 보낸다
 */
@ConfigurationProperties(prefix = "zipick.notification")
public record NotificationProperties(String baseUrl, String internalKey, Duration pollInterval, boolean schedulerEnabled,
                                     boolean queueEnabled) {
    public NotificationProperties {
        if (pollInterval == null) pollInterval = Duration.ofMinutes(5);
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8081";
    }
    public boolean configured() { return internalKey != null && !internalKey.isBlank(); }
}
