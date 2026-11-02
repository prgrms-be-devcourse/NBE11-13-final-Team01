package com.zipick.notification.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 알림 설정 화면의 뒷단. 설정은 메인 DB 에, 발송·내역은 알림센터에.
 * 알림센터는 "보내라"는 요청만 받으므로 누구에게·어느 채널로 보낼지는 여기 설정을 보고 정한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/";

    private final NotificationSettingRepository settings;
    private final NotificationClient client;
    private final NotificationChannelPolicy policy;

    @Transactional(readOnly = true)
    public NotificationSettingResponse getSetting(Long userId) {
        NotificationSetting s = settings.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
        return NotificationSettingResponse.from(s, client.emailAvailable(), policy.slackAllowed(userId));
    }

    @Transactional
    public NotificationSettingResponse saveSetting(Long userId, NotificationSettingRequest r) {
        // 알림센터도 같은 검사를 하지만, 사용자에게 바로 알려 주기 위해 여기서 거른다
        if (r.slackWebhookUrl() != null && !r.slackWebhookUrl().isBlank() && !r.slackWebhookUrl().trim().startsWith(SLACK_WEBHOOK_PREFIX)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "슬랙 웹훅 주소는 https://hooks.slack.com/ 으로 시작해야 합니다.");
        }
        boolean slackAllowed = policy.slackAllowed(userId);
        // 슬랙은 운영자만. 일반 사용자가 웹훅을 넣으면 바로 알려 준다 (이메일만 받는다)
        if (!slackAllowed && r.slackWebhookUrl() != null && !r.slackWebhookUrl().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "슬랙 알림은 운영자 계정만 쓸 수 있어요. 이메일로 받아 주세요.");
        }
        NotificationSetting s = settings.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
        s.apply(r, LocalDateTime.now(NotificationScheduler.SEOUL));
        // 운영자였다가 일반 사용자가 된 경우 남아 있던 웹훅은 지운다
        if (!slackAllowed) s.clearSlack();
        return NotificationSettingResponse.from(settings.save(s), client.emailAvailable(), slackAllowed);
    }

    public List<NotificationEventResponse> history(Long userId) { return client.history(userId); }
}
