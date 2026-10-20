package com.zipick.notification.service;

import com.zipick.global.error.BusinessException;
import com.zipick.interest.domain.InterestNotice;
import com.zipick.interest.dto.NoticeSummary;
import com.zipick.interest.repository.InterestNoticeRepository;
import com.zipick.interest.repository.NoticeSummaryQueryRepository;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.domain.NotificationCursor;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.queue.NotificationPriority;
import com.zipick.notification.repository.NotificationCursorRepository;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.service.RecommendationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "무엇을 알릴지"는 메인 서버가 안다(관심 공고, 추천 엔진). 그래서 찾는 건 여기서 하고, 보내는 건 알림센터에 맡긴다.
 * 주기마다 ① 관심 공고의 접수 마감 D-day ② 마지막 확인 이후 수집된 공고 중 사용자 조건에 맞는 것(역매칭) 을 찾아
 * 알림센터에 템플릿 코드 + 변수 + dedupeKey 로 요청한다. 같은 키는 알림센터가 한 번만 적으므로 중복 발송이 없다.
 * 요청은 큐로 던지므로(queue-enabled) 알림센터가 꺼져 있어도 남는다. 큐도 없고 알림센터도 꺼져 있으면
 * 이번 주기는 건너뛴다(커서를 옮기지 않아 다음 주기에 다시 시도).
 */
@Component
@ConditionalOnProperty(prefix = "zipick.notification", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NotificationClient client;
    private final InterestNoticeRepository interestRepository;
    private final NoticeSummaryQueryRepository noticeRepository;
    private final NotificationCursorRepository cursorRepository;
    private final RecommendationService recommendationService;

    @Scheduled(fixedDelayString = "${zipick.notification.poll-interval:PT5M}", initialDelayString = "PT30S")
    public void run() {
        if (!client.configured()) return;
        try {
            List<NotificationSettingResponse> subscribers = client.listEnabledSubscriptions();
            int a = enqueueDeadlineReminders(subscribers);
            int b = enqueueNewMatches(subscribers);
            if (a + b > 0) log.info("알림 스케줄러: 마감 {}건, 새 공고 {}건을 알림센터에 요청", a, b);
        } catch (BusinessException e) {
            log.warn("알림센터에 연결하지 못해 이번 주기를 건너뜁니다: {}", e.getMessage());
        } catch (Exception e) {
            log.error("알림 스케줄러 실패", e);
        }
    }

    /** 관심 공고의 접수 마감 D-day 알림 */
    @Transactional(readOnly = true)
    public int enqueueDeadlineReminders(List<NotificationSettingResponse> subscribers) {
        LocalDate today = LocalDate.now(SEOUL);
        int count = 0;
        for (NotificationSettingResponse s : subscribers) {
            List<InterestNotice> saved = interestRepository.findAllByUserIdOrderByIdDesc(s.userId());
            if (saved.isEmpty()) continue;
            Map<Long, NoticeSummary> notices = noticeRepository.findByIds(saved.stream().map(InterestNotice::getNoticeId).toList());
            for (InterestNotice in : saved) {
                NoticeSummary n = notices.get(in.getNoticeId());
                if (n == null || n.applyEndAt() == null) continue;
                int left = (int) ChronoUnit.DAYS.between(today, n.applyEndAt());
                if (!s.deadlineDays().contains(left)) continue;
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("dDayLabel", left == 0 ? "D-day" : "D-" + left);
                vars.put("whenText", left == 0 ? "오늘" : left + "일 뒤");
                vars.put("applyEndAt", n.applyEndAt().toString());
                vars.put("noticeTitle", n.title());
                vars.put("regionsText", n.regions().isEmpty() ? "" : " (" + String.join(", ", n.regions()) + ")");
                vars.put("detailUrl", n.detailUrl());
                client.request(NotificationRequest.forUser("DEADLINE", s.userId(), vars,
                        "DEADLINE:" + s.userId() + ":" + n.noticeId() + ":" + left), NotificationPriority.NORMAL);
                count++;
            }
        }
        return count;
    }

    /** 마지막 확인 이후 수집된 공고를 각 사용자 조건으로 추천해 보고, 순위 묶음에 들면 알림 */
    @Transactional
    public int enqueueNewMatches(List<NotificationSettingResponse> subscribers) {
        LocalDateTime now = LocalDateTime.now(SEOUL);
        NotificationCursor cursor = cursorRepository.findById(NotificationCursor.NEW_MATCH).orElse(null);
        if (cursor == null) {
            // 처음 켜질 때는 지금까지의 공고를 전부 '새 공고'로 보내지 않도록 기준점만 찍는다
            cursorRepository.save(new NotificationCursor(NotificationCursor.NEW_MATCH, now));
            return 0;
        }
        List<Long> newIds = noticeRepository.findCreatedAfter(cursor.getCheckedAt());
        if (newIds.isEmpty()) { cursor.advance(now); return 0; }

        Set<Long> noticeIds = new HashSet<>(newIds);
        Map<Long, NoticeSummary> notices = noticeRepository.findByIds(newIds);
        int count = 0;
        for (NotificationSettingResponse s : subscribers) {
            if (!s.newMatchEnabled()) continue;
            List<HouseScore> ranked;
            try {
                ranked = recommendationService.rankForNotices(s.userId(), noticeIds);
            } catch (Exception e) {
                continue;   // 내 조건을 아직 안 적은 사용자
            }
            Set<Long> done = new HashSet<>();
            for (HouseScore h : ranked) {
                Long nid = h.candidate().noticeId();
                if (!done.add(nid)) continue;
                NoticeSummary n = notices.get(nid);
                if (n == null) continue;
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("noticeTitle", n.title());
                vars.put("score", h.score());
                vars.put("region", h.candidate().regionKey());
                vars.put("deadlineText", n.applyEndAt() == null ? "" : " · 접수 마감 " + n.applyEndAt());
                vars.put("detailUrl", n.detailUrl());
                client.request(NotificationRequest.forUser("NEW_MATCH", s.userId(), vars,
                        "NEW_MATCH:" + s.userId() + ":" + nid), NotificationPriority.NORMAL);
                count++;
            }
        }
        // 전부 요청한 뒤에 커서를 옮긴다 — 중간에 실패하면 다음 주기에 같은 공고를 다시 본다(dedupeKey 로 중복 없음)
        cursor.advance(now);
        return count;
    }
}
