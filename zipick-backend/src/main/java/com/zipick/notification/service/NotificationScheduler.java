package com.zipick.notification.service;

import com.zipick.interest.domain.InterestNotice;
import com.zipick.interest.dto.NoticeSummary;
import com.zipick.interest.repository.InterestNoticeRepository;
import com.zipick.interest.repository.NoticeSummaryQueryRepository;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.domain.NotificationCursor;
import com.zipick.notification.domain.NotificationEvent;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.repository.NotificationCursorRepository;
import com.zipick.notification.repository.NotificationSettingRepository;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.service.RecommendationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
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
 * 알림 스케줄러 (DB 폴링). 주기마다 세 가지를 한다.
 *
 * 1. 마감 알림 적기: 관심 공고의 접수 마감이 설정한 D-day(기본 3·1·0일 전)인 사용자마다 이벤트를 적는다
 * 2. 새 공고 알림 적기: 마지막 확인 이후 수집된 공고를 사용자 조건으로 추천해 보고, 순위 묶음에 들면 적는다 (집 → 사용자 역매칭)
 * 3. 보내기: PENDING 이벤트를 채널로 보낸다
 *
 * 같은 알림은 dedupe_key 로 한 번만 적히므로, 주기가 겹치거나 실패 후 다시 돌아도 중복 발송되지 않는다.
 * 서버가 여러 대가 되면 2·3번에 잠금이 필요하다 (지금은 1대 전제).
 */
@Component
@ConditionalOnProperty(prefix = "zipick.notification", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    private final NotificationService notificationService;
    private final NotificationSettingRepository settingRepository;
    private final InterestNoticeRepository interestRepository;
    private final NoticeSummaryQueryRepository noticeRepository;
    private final NotificationCursorRepository cursorRepository;
    private final RecommendationService recommendationService;
    private final NotificationProperties props;

    @Scheduled(fixedDelayString = "${zipick.notification.poll-interval:PT5M}", initialDelayString = "PT30S")
    public void run() {
        try {
            int a = enqueueDeadlineReminders();
            int b = enqueueNewMatches();
            int c = notificationService.dispatchPending();
            if (a + b + c > 0) log.info("알림 스케줄러: 마감 {}건 적음, 새 공고 {}건 적음, {}건 보냄", a, b, c);
            else log.debug("알림 스케줄러: 할 일 없음");
        } catch (Exception e) {
            log.error("알림 스케줄러 실패", e);
        }
    }

    /** 관심 공고의 접수 마감 D-day 알림 */
    @Transactional
    public int enqueueDeadlineReminders() {
        LocalDate today = LocalDate.now(NotificationService.SEOUL);
        LocalDateTime now = LocalDateTime.now(NotificationService.SEOUL);
        int count = 0;
        for (NotificationSetting s : settingRepository.findAllByEnabledTrue()) {
            List<InterestNotice> saved = interestRepository.findAllByUserIdOrderByIdDesc(s.getUserId());
            if (saved.isEmpty()) continue;
            Map<Long, NoticeSummary> notices = noticeRepository.findByIds(saved.stream().map(InterestNotice::getNoticeId).toList());
            for (InterestNotice in : saved) {
                NoticeSummary n = notices.get(in.getNoticeId());
                if (n == null || n.applyEndAt() == null) continue;
                int left = (int) ChronoUnit.DAYS.between(today, n.applyEndAt());
                if (!s.deadlineDayList().contains(left)) continue;
                String when = left == 0 ? "오늘" : left + "일 뒤";
                boolean added = notificationService.enqueue(s.getUserId(), NotificationEvent.Type.DEADLINE, n.noticeId(),
                        "DEADLINE:" + s.getUserId() + ":" + n.noticeId() + ":" + left,
                        "접수 마감 " + (left == 0 ? "D-day" : "D-" + left) + " · " + n.title(),
                        "관심 공고의 접수가 " + when + "(" + n.applyEndAt() + ") 마감돼요." + regionsText(n),
                        n.detailUrl(), now);
                if (added) count++;
            }
        }
        return count;
    }

    /** 마지막 확인 이후 수집된 공고를 각 사용자 조건으로 추천해 보고, 순위 묶음에 들면 알림 */
    @Transactional
    public int enqueueNewMatches() {
        LocalDateTime now = LocalDateTime.now(NotificationService.SEOUL);
        NotificationCursor cursor = cursorRepository.findById(NotificationCursor.NEW_MATCH).orElse(null);
        if (cursor == null) {
            // 처음 켜질 때는 지금까지의 공고를 전부 '새 공고'로 보내지 않도록 기준점만 찍는다
            cursorRepository.save(new NotificationCursor(NotificationCursor.NEW_MATCH, now));
            return 0;
        }
        List<Long> newIds = noticeRepository.findCreatedAfter(cursor.getCheckedAt());
        cursor.advance(now);
        if (newIds.isEmpty()) return 0;

        Set<Long> noticeIds = new HashSet<>(newIds);
        Map<Long, NoticeSummary> notices = noticeRepository.findByIds(newIds);
        int count = 0;
        for (NotificationSetting s : settingRepository.findAllByEnabledTrue()) {
            if (!s.isNewMatchEnabled()) continue;
            List<HouseScore> ranked;
            try {
                ranked = recommendationService.rankForNotices(s.getUserId(), noticeIds);
            } catch (Exception e) {
                continue;   // 내 조건을 아직 안 적은 사용자
            }
            Set<Long> done = new HashSet<>();
            for (HouseScore h : ranked) {
                Long nid = h.candidate().noticeId();
                if (!done.add(nid)) continue;
                NoticeSummary n = notices.get(nid);
                if (n == null) continue;
                boolean added = notificationService.enqueue(s.getUserId(), NotificationEvent.Type.NEW_MATCH, nid,
                        "NEW_MATCH:" + s.getUserId() + ":" + nid,
                        "내 조건에 맞는 새 공고 · " + n.title(),
                        "추천도 " + h.score() + "점 · " + h.candidate().regionKey()
                                + (n.applyEndAt() == null ? "" : " · 접수 마감 " + n.applyEndAt()),
                        n.detailUrl(), now);
                if (added) count++;
            }
        }
        return count;
    }

    private static String regionsText(NoticeSummary n) {
        return n.regions().isEmpty() ? "" : " (" + String.join(", ", n.regions()) + ")";
    }
}
