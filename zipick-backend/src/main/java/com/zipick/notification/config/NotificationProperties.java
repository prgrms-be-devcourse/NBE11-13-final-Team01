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
 * @param appUrl       알림 문구에 넣는 서비스 주소 (수신 거부 안내 등)
 * @param opsSlackWebhookUrl 운영 알림(공고 수집 일일 보고)을 받을 슬랙 웹훅. 비우면 슬랙으로 보내지 않는다
 * @param opsEmail     운영 알림을 받을 메일. 비우면 메일로 보내지 않는다
 */
@ConfigurationProperties(prefix = "zipick.notification")
public record NotificationProperties(String baseUrl, String internalKey, Duration pollInterval, boolean schedulerEnabled,
                                     boolean queueEnabled, String appUrl, String opsSlackWebhookUrl, String opsEmail) {
    public NotificationProperties {
        if (pollInterval == null) pollInterval = Duration.ofMinutes(5);
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8081";
        if (appUrl == null || appUrl.isBlank()) appUrl = "http://localhost:5173";
        opsSlackWebhookUrl = blankToNull(opsSlackWebhookUrl);
        opsEmail = blankToNull(opsEmail);
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    public boolean configured() { return internalKey != null && !internalKey.isBlank(); }
}
