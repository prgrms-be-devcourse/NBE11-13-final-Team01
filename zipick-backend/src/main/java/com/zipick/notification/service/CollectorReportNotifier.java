package com.zipick.notification.service;

import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.queue.NotificationPriority;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공고 수집 일일 보고 — 알림센터 스펙을 다른 서비스가 쓰는 견본.
 * 수집이 끝나면 결과를 COLLECTOR_DAILY_REPORT 템플릿으로 운영 슬랙·운영자 메일에 보낸다.
 * 받는 곳은 설정(zipick.notification.ops-*)이 정한다. 둘 다 비어 있으면 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollectorReportNotifier {

    public static final String SOURCE = "collector";
    public static final String TEMPLATE = "COLLECTOR_DAILY_REPORT";

    private final NotificationClient client;
    private final NotificationProperties props;

    /** @return 요청을 보냈으면 true */
    public boolean report(NoticeSyncResponse r) {
        if (!client.configured()) return false;
        List<String> channels = new ArrayList<>(2);
        if (props.opsEmail() != null) channels.add("email");
        if (props.opsSlackWebhookUrl() != null) channels.add("slack");
        if (channels.isEmpty()) return false;

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("date", r.startedAt().atZone(NotificationScheduler.SEOUL).toLocalDate().toString());
        vars.put("collectedRows", r.collectedRows());
        vars.put("createdNotices", r.createdNotices());
        vars.put("updatedNotices", r.updatedNotices());
        vars.put("missingClosedNotices", r.missingClosedNotices());
        vars.put("synchronizedHousings", r.synchronizedHousings());
        vars.put("elapsedSeconds", Duration.between(r.startedAt(), r.finishedAt()).toSeconds());
        NotificationRequest req = new NotificationRequest(SOURCE, TEMPLATE, channels,
                new NotificationRequest.Recipient(props.opsEmail(), props.opsSlackWebhookUrl()), vars,
                "COLLECTOR_REPORT:" + r.startedAt(), null, Map.of("kind", "collector-report"), null);
        try {
            client.request(req, NotificationPriority.LOW);
            return true;
        } catch (RuntimeException e) {
            log.warn("수집 보고를 알림센터에 보내지 못함: {}", e.getMessage());
            return false;
        }
    }
}
