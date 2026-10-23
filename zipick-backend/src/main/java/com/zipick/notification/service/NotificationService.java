package com.zipick.notification.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    public static final String TEMPLATE_TEST = "MAIN_TEST";

    private final NotificationSettingRepository settings;
    private final NotificationClient client;

    @Transactional(readOnly = true)
    public NotificationSettingResponse getSetting(Long userId) {
        NotificationSetting s = settings.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
        return NotificationSettingResponse.from(s, client.emailAvailable());
    }

    @Transactional
    public NotificationSettingResponse saveSetting(Long userId, NotificationSettingRequest r) {
        // 알림센터도 같은 검사를 하지만, 사용자에게 바로 알려 주기 위해 여기서 거른다
        if (r.slackWebhookUrl() != null && !r.slackWebhookUrl().isBlank() && !r.slackWebhookUrl().trim().startsWith(SLACK_WEBHOOK_PREFIX)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "슬랙 웹훅 주소는 https://hooks.slack.com/ 으로 시작해야 합니다.");
        }
        NotificationSetting s = settings.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
        s.apply(r, LocalDateTime.now(NotificationScheduler.SEOUL));
        return NotificationSettingResponse.from(settings.save(s), client.emailAvailable());
    }

    /** 저장된 주소 중 지금 보낼 수 있는 채널로 바로 보내고 채널별 결과를 돌려준다 (알림센터 delivery=now) */
    @Transactional(readOnly = true)
    public NotificationEventResponse sendTest(Long userId) {
        NotificationSetting s = settings.findById(userId)
                .filter(x -> !x.channels().isEmpty())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "슬랙 웹훅 또는 이메일을 먼저 저장해 주세요."));
        List<String> channels = client.usableChannels(s.channels());
        if (channels.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 보낼 수 있는 채널이 없어요. 알림센터에 이메일 발송 설정이 없으면 슬랙 웹훅을 넣어 주세요.");
        Map<String, Object> vars = Map.of("deadlineDays", String.join(", ", s.deadlineDayList().stream().map(String::valueOf).toList()));
        return client.enqueue(NotificationRequest.toUser(TEMPLATE_TEST, s, channels, vars, null).now());
    }

    public List<NotificationEventResponse> history(Long userId) { return client.history(userId); }
}
