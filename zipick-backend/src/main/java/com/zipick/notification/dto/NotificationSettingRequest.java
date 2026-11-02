package com.zipick.notification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 알림센터의 SubscriptionRequest 와 같은 모양. slackWebhookUrl/email 은 null=유지, ""=삭제 */
public record NotificationSettingRequest(
        boolean enabled,
        @Size(max = 500) String slackWebhookUrl,
        @Email @Size(max = 255) String email,
        /** 광고성 정보 수신 동의. null 이면 그대로 */
        Boolean marketingConsent,
        /** 접수 마감 며칠 전에 알릴지 "3,1,0". null=그대로, ""=마감 알림 끔. 0~30일 */
        @Pattern(regexp = "^\\s*$|^\\s*([12]?\\d|30)(\\s*,\\s*([12]?\\d|30))*\\s*$", message = "마감 알림은 0~30일 전을 쉼표로 구분해 적어 주세요 (예: 3,1,0)")
        String deadlineDays,
        boolean newMatchEnabled
) {}
