package com.zipick.notification.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.channel.NotificationChannel;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.domain.NotificationEvent;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.repository.NotificationEventRepository;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 설정과 아웃박스 발송.
 *
 * 흐름: 이벤트를 notification_events 에 PENDING 으로 적는다 → dispatchPending() 이 읽어 채널로 보낸다 → SENT / FAILED.
 * 보내는 쪽(스케줄러)과 적는 쪽이 분리돼 있어 서버가 죽어도 적어 둔 알림은 다음 주기에 나간다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NotificationSettingRepository settingRepository;
    private final NotificationEventRepository eventRepository;
    private final List<NotificationChannel> channels;
    private final NotificationProperties props;
    private final PlatformTransactionManager txManager;

    // ---- 설정 ----

    @Transactional(readOnly = true)
    public NotificationSettingResponse getSetting(Long userId) {
        return settingRepository.findByUserId(userId)
                .map(s -> NotificationSettingResponse.from(s, emailAvailable()))
                .orElseGet(() -> NotificationSettingResponse.empty(emailAvailable()));
    }

    @Transactional
    public NotificationSettingResponse saveSetting(Long userId, NotificationSettingRequest r) {
        if (r.slackWebhookUrl() != null && !r.slackWebhookUrl().isBlank()
                && !r.slackWebhookUrl().trim().startsWith(NotificationSetting.SLACK_WEBHOOK_PREFIX)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "슬랙 웹훅 주소는 https://hooks.slack.com/ 으로 시작해야 합니다.");
        }
        NotificationSetting s = settingRepository.findByUserId(userId)
                .map(x -> { x.update(r.enabled(), r.slackWebhookUrl(), r.email(), r.deadlineDays(), r.newMatchEnabled()); return x; })
                .orElseGet(() -> settingRepository.save(NotificationSetting.builder()
                        .userId(userId).enabled(r.enabled()).slackWebhookUrl(r.slackWebhookUrl()).email(r.email())
                        .deadlineDays(r.deadlineDays()).newMatchEnabled(r.newMatchEnabled()).build()));
        return NotificationSettingResponse.from(s, emailAvailable());
    }

    public boolean emailAvailable() {
        return channels.stream().anyMatch(c -> c.name().equals("email"));
    }

    // ---- 이벤트 ----

    @Transactional(readOnly = true)
    public List<NotificationEventResponse> history(Long userId) {
        return eventRepository.findTop50ByUserIdOrderByIdDesc(userId).stream().map(NotificationEventResponse::from).toList();
    }

    /** 아웃박스에 적는다. 같은 dedupeKey 가 이미 있으면 적지 않는다 (멱등) */
    @Transactional
    public boolean enqueue(Long userId, NotificationEvent.Type type, Long noticeId, String dedupeKey,
                           String title, String body, String url, LocalDateTime scheduledFor) {
        if (eventRepository.existsByDedupeKey(dedupeKey)) return false;
        eventRepository.save(NotificationEvent.builder().userId(userId).type(type).noticeId(noticeId).dedupeKey(dedupeKey)
                .title(title).body(body).url(url).scheduledFor(scheduledFor).build());
        return true;
    }

    /** 설정 화면의 "테스트 발송": 바로 적고 바로 보낸다 */
    @Transactional
    public NotificationEventResponse sendTest(Long userId) {
        NotificationSetting s = settingRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "알림 설정을 먼저 저장해 주세요."));
        if (channels.stream().noneMatch(c -> c.supports(s))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "보낼 채널이 없습니다. 슬랙 웹훅 주소나 이메일을 넣어 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(SEOUL);
        NotificationEvent e = eventRepository.save(NotificationEvent.builder().userId(userId).type(NotificationEvent.Type.TEST)
                .dedupeKey("TEST:" + userId + ":" + now)
                .title("ZIPICK 알림 테스트")
                .body("알림이 잘 도착했어요. 관심 공고의 접수 마감 " + daysText(s) + "일 전과, 내 조건에 맞는 새 공고가 들어올 때 알려 드려요.")
                .url(null).scheduledFor(now).build());
        deliver(e, s, 1);   // 테스트는 재시도하지 않는다. 바로 결과를 보여 준다
        return NotificationEventResponse.from(e);
    }

    /**
     * PENDING 이벤트를 읽어 보낸다. 반환값은 보낸 건수.
     * 이벤트마다 새 트랜잭션(TransactionTemplate)이라 하나가 실패해도 나머지는 진행되고, 결과(SENT/FAILED)가 바로 저장된다.
     * (같은 빈 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 안 걸리므로 템플릿을 쓴다)
     */
    public int dispatchPending() {
        LocalDateTime now = LocalDateTime.now(SEOUL);
        List<Long> ids = eventRepository.findTop100ByStatusAndScheduledForLessThanEqualOrderByIdAsc(
                NotificationEvent.Status.PENDING, now).stream().map(NotificationEvent::getId).toList();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        int sent = 0;
        for (Long id : ids) {
            Boolean ok = tx.execute(status -> dispatchOne(id));
            if (Boolean.TRUE.equals(ok)) sent++;
        }
        return sent;
    }

    private boolean dispatchOne(Long eventId) {
        NotificationEvent e = eventRepository.findById(eventId).orElse(null);
        if (e == null || e.getStatus() != NotificationEvent.Status.PENDING) return false;
        NotificationSetting s = settingRepository.findByUserId(e.getUserId()).orElse(null);
        if (s == null || !s.isEnabled()) {
            e.markSkipped("알림이 꺼져 있음");
            return false;
        }
        return deliver(e, s, props.maxAttempts());
    }

    private static String daysText(NotificationSetting s) {
        return s.deadlineDayList().stream().sorted(java.util.Comparator.reverseOrder())
                .map(String::valueOf).collect(java.util.stream.Collectors.joining("·"));
    }

    private boolean deliver(NotificationEvent e, NotificationSetting s, int maxAttempts) {
        List<String> ok = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (NotificationChannel c : channels) {
            if (!c.supports(s)) continue;
            try {
                c.send(s, e);
                ok.add(c.name());
            } catch (Exception ex) {
                log.warn("알림 발송 실패 event={} channel={}: {}", e.getId(), c.name(), ex.toString());
                errors.add(c.name() + ": " + ex.getMessage());
            }
        }
        if (!ok.isEmpty()) {
            e.markSent(String.join(",", ok), LocalDateTime.now(SEOUL));
            return true;
        }
        e.markFailed(errors.isEmpty() ? "보낼 채널이 없음" : String.join(" / ", errors), maxAttempts);
        return false;
    }
}
