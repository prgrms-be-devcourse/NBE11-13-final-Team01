package com.zipick.notification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NotificationSettingRequest(
        boolean enabled,
        @Size(max = 500) String slackWebhookUrl,
        @Email @Size(max = 255) String email,
        /** 예: "3,1,0" */
        @Pattern(regexp = "^\\s*$|^\\d+(\\s*,\\s*\\d+)*$", message = "마감 알림 일수는 숫자를 쉼표로 구분해 적어 주세요 (예: 3,1,0)")
        String deadlineDays,
        boolean newMatchEnabled
) {}
