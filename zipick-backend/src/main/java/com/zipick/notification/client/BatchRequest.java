package com.zipick.notification.client;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 묶음 요청 (POST /internal/v1/batches): 같은 템플릿·채널·시각으로 수신자 여러 명.
 * 누구에게 보낼지(대상 선정)는 여기서 정해서 보낸다. 알림센터는 받은 목록을 한 번에 적고 묶음 id 로 취소·집계만 한다.
 * 수신자 모두가 channels 의 주소를 가져야 한다 (하나라도 빠지면 묶음 전체가 400).
 */
public record BatchRequest(
        String source,
        String templateCode,
        List<String> channels,
        LocalDateTime scheduledAt,
        Map<String, String> tags,
        List<Recipient> recipients
) {
    /** @param tags 묶음 tags 위에 덧씌운다 (user:{id} 로 사용자별 내역에 보이게) */
    public record Recipient(NotificationRequest.Recipient to, Map<String, Object> variables, String dedupeKey, Map<String, String> tags) {}
}
