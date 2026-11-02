package com.zipick.notification.client;

import com.zipick.notification.domain.NotificationSetting;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 알림센터 스펙 v1 의 "알림 요청" (docs/notification-api.md).
 * 알림센터는 판단하지 않는다 — 누구에게(to), 어떤 채널로(channels), 언제(scheduledAt) 보낼지는 여기서 다 정해서 보낸다.
 * 문구는 알림센터의 템플릿(templates/*.yaml)이 가지므로 코드와 변수만 넘긴다. 큐로 보내든 HTTP 로 보내든 같은 JSON.
 *
 * @param source      요청한 서비스. 메인 서버는 항상 {@link #SOURCE}
 * @param channels    email, slack 중 고른 것. 고른 채널의 주소가 to 에 있어야 한다 (없으면 알림센터가 400)
 * @param dedupeKey   같은 키는 알림센터가 한 번만 적는다 (스케줄러가 같은 공고를 두 번 봐도 중복 발송 없음)
 * @param scheduledAt null 이면 바로
 * @param tags        알림센터가 해석하지 않는 꼬리표. 내역 조회에 쓴다 (user:{id})
 * @param delivery    "now" 면 알림센터가 기다리지 않고 바로 보내고 결과를 돌려준다 (테스트 발송용). 평소엔 null
 */
public record NotificationRequest(
        String source,
        String templateCode,
        List<String> channels,
        Recipient to,
        Map<String, Object> variables,
        String dedupeKey,
        LocalDateTime scheduledAt,
        Map<String, String> tags,
        String delivery
) {
    public static final String SOURCE = "main";
    public static final String TAG_USER = "user";

    public record Recipient(String email, String slackWebhookUrl) {}

    /** 사용자의 알림 설정에 적힌 주소로. 채널은 호출한 쪽이 고른다 (보통 {@code client.usableChannels(s.channels())}); 비어 있으면 안 된다 */
    public static NotificationRequest toUser(String templateCode, NotificationSetting s, List<String> channels, Map<String, Object> variables, String dedupeKey) {
        return new NotificationRequest(SOURCE, templateCode, channels, s.recipient(), variables, dedupeKey, null,
                Map.of(TAG_USER, String.valueOf(s.getUserId())), null);
    }

    /** 가입 인증처럼 아직 계정이 없을 때: 이메일로 직접 */
    public static NotificationRequest toEmail(String templateCode, String email, Map<String, Object> variables) {
        return new NotificationRequest(SOURCE, templateCode, List.of("email"), new Recipient(email, null), variables, null, null, Map.of(), null);
    }

    /** 기다리지 않고 바로 보내고 결과를 받는다 (HTTP 로만 의미가 있다) */
    public NotificationRequest now() {
        return new NotificationRequest(source, templateCode, channels, to, variables, dedupeKey, scheduledAt, tags, "now");
    }
}
