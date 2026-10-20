package com.zipick.notification.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사용자 알림 설정 화면의 뒷단. 저장·발송·내역은 전부 알림센터가 가지고, 여기서는 넘겨 주기만 한다.
 * (메인 서버는 사용자가 누구인지(JWT)만 확인하고, 알림센터는 사용자 id 만 받는다)
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/";

    private final NotificationClient client;

    public NotificationSettingResponse getSetting(Long userId) { return client.getSubscription(userId); }

    public NotificationSettingResponse saveSetting(Long userId, NotificationSettingRequest r) {
        // 알림센터도 같은 검사를 하지만, 사용자에게 바로 알려 주기 위해 여기서도 한 번 거른다
        if (r.slackWebhookUrl() != null && !r.slackWebhookUrl().isBlank() && !r.slackWebhookUrl().trim().startsWith(SLACK_WEBHOOK_PREFIX)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "슬랙 웹훅 주소는 https://hooks.slack.com/ 으로 시작해야 합니다.");
        }
        return client.putSubscription(userId, r);
    }

    public NotificationEventResponse sendTest(Long userId) { return client.sendTest(userId); }

    public List<NotificationEventResponse> history(Long userId) { return client.history(userId); }
}
