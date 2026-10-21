package com.zipick.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.domain.Candidate;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 메인 서버의 알림 스케줄러: "무엇을 알릴지"를 찾아 알림센터에 요청한다.
 * ① 관심 공고 접수 마감이 사용자가 고른 D-day 와 같은 날 ② 마지막 확인 이후 수집된 공고 중 사용자 조건에 맞는 것
 */
@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Mock NotificationClient client;
    @Mock InterestNoticeRepository interestRepository;
    @Mock NoticeSummaryQueryRepository noticeRepository;
    @Mock NotificationCursorRepository cursorRepository;
    @Mock RecommendationService recommendationService;
    @InjectMocks NotificationScheduler scheduler;

    private static NotificationSettingResponse subscriber(long userId, boolean newMatch) {
        return new NotificationSettingResponse(userId, true, true, null, null, false, null, List.of(3, 1, 0), newMatch, false);
    }

    private static NoticeSummary notice(long id, LocalDate applyEnd, List<String> regions) {
        return new NoticeSummary(id, "공고 " + id, "행복주택", null, null, "LH", "https://lh.or.kr/" + id,
                null, null, applyEnd, null, 1, regions);
    }

    private static InterestNotice saved(long noticeId) {
        return InterestNotice.builder().userId(1L).noticeId(noticeId).build();
    }

    @SuppressWarnings("unchecked")
    private List<NotificationRequest> capturedRequests(int times) {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client, times(times)).request(captor.capture(), eq(NotificationPriority.NORMAL));
        return captor.getAllValues();
    }

    @Test
    void 마감_D_day_가_설정한_날짜와_같은_관심_공고만_요청한다() {
        when(interestRepository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of(saved(10), saved(11), saved(12), saved(13)));
        when(noticeRepository.findByIds(anyList())).thenReturn(Map.of(
                10L, notice(10, TODAY.plusDays(3), List.of("경상북도 구미시")),   // D-3 → 알림
                11L, notice(11, TODAY.plusDays(2), List.of()),                  // D-2 → 설정에 없음
                12L, notice(12, null, List.of()),                               // 마감일 없음
                13L, notice(13, TODAY, List.of())));                            // D-day → 알림

        int count = scheduler.enqueueDeadlineReminders(List.of(subscriber(1, true)));

        assertThat(count).isEqualTo(2);
        List<NotificationRequest> reqs = capturedRequests(2);
        assertThat(reqs).extracting(NotificationRequest::dedupeKey).containsExactly("DEADLINE:1:10:3", "DEADLINE:1:13:0");
        assertThat(reqs.get(0).variables()).containsEntry("dDayLabel", "D-3").containsEntry("whenText", "3일 뒤")
                .containsEntry("regionsText", " (경상북도 구미시)");
        assertThat(reqs.get(1).variables()).containsEntry("dDayLabel", "D-day").containsEntry("whenText", "오늘")
                .containsEntry("regionsText", "");
        assertThat(reqs.get(0).templateCode()).isEqualTo("DEADLINE");
    }

    @Test
    void 관심_공고가_없으면_공고를_조회하지_않는다() {
        when(interestRepository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of());
        assertThat(scheduler.enqueueDeadlineReminders(List.of(subscriber(1, true)))).isZero();
        verify(noticeRepository, never()).findByIds(anyList());
    }

    @Test
    void 처음_실행이면_기준점만_찍고_새_공고_알림을_보내지_않는다() {
        when(cursorRepository.findById(NotificationCursor.NEW_MATCH)).thenReturn(Optional.empty());
        assertThat(scheduler.enqueueNewMatches(List.of(subscriber(1, true)))).isZero();
        verify(cursorRepository).save(any(NotificationCursor.class));
        verify(client, never()).request(any(), any());
    }

    @Test
    void 새_공고가_없으면_커서만_옮긴다() {
        LocalDateTime before = LocalDateTime.now().minusHours(1);
        NotificationCursor cursor = new NotificationCursor(NotificationCursor.NEW_MATCH, before);
        when(cursorRepository.findById(NotificationCursor.NEW_MATCH)).thenReturn(Optional.of(cursor));
        when(noticeRepository.findCreatedAfter(before)).thenReturn(List.of());

        assertThat(scheduler.enqueueNewMatches(List.of(subscriber(1, true)))).isZero();
        assertThat(cursor.getCheckedAt()).isAfter(before);
    }

    @Test
    void 새_공고는_사용자_조건으로_추천해_보고_공고마다_한_번만_요청한다() {
        LocalDateTime before = LocalDateTime.now().minusHours(1);
        NotificationCursor cursor = new NotificationCursor(NotificationCursor.NEW_MATCH, before);
        when(cursorRepository.findById(NotificationCursor.NEW_MATCH)).thenReturn(Optional.of(cursor));
        when(noticeRepository.findCreatedAfter(before)).thenReturn(List.of(10L, 11L));
        when(noticeRepository.findByIds(List.of(10L, 11L))).thenReturn(Map.of(
                10L, notice(10, TODAY.plusDays(5), List.of()), 11L, notice(11, null, List.of())));
        // 같은 공고(10)의 주택 두 곳 + 공고 11 한 곳
        List<HouseScore> ranked = List.of(score(10, 87), score(10, 80), score(11, 72));   // 목은 when(...) 밖에서 만든다
        when(recommendationService.rankForNotices(1L, Set.of(10L, 11L))).thenReturn(ranked);
        // 조건을 아직 안 적은 사용자는 건너뛴다
        when(recommendationService.rankForNotices(eq(3L), any())).thenThrow(new BusinessException(ErrorCode.PROFILE_NOT_FOUND));

        int count = scheduler.enqueueNewMatches(List.of(subscriber(1, true), subscriber(2, false), subscriber(3, true)));

        assertThat(count).isEqualTo(2);
        List<NotificationRequest> reqs = capturedRequests(2);
        assertThat(reqs).extracting(NotificationRequest::dedupeKey).containsExactly("NEW_MATCH:1:10", "NEW_MATCH:1:11");
        assertThat(reqs.get(0).variables()).containsEntry("score", 87).containsEntry("region", "경상북도 구미시");
        assertThat((String) reqs.get(0).variables().get("deadlineText")).contains("접수 마감");
        assertThat(reqs.get(1).variables()).containsEntry("deadlineText", "");
        verify(recommendationService, never()).rankForNotices(eq(2L), any());   // 새 공고 알림을 끈 사용자
        assertThat(cursor.getCheckedAt()).isAfter(before);
    }

    private static HouseScore score(long noticeId, int score) {
        Candidate c = mock(Candidate.class);
        when(c.noticeId()).thenReturn(noticeId);
        org.mockito.Mockito.lenient().when(c.regionKey()).thenReturn("경상북도 구미시");
        HouseScore h = mock(HouseScore.class);
        when(h.candidate()).thenReturn(c);
        org.mockito.Mockito.lenient().when(h.score()).thenReturn(score);
        return h;
    }

    @Test
    void 키가_없으면_아무것도_하지_않는다() {
        when(client.configured()).thenReturn(false);
        scheduler.run();
        verify(client, never()).listEnabledSubscriptions();
    }

    @Test
    void 알림센터에_닿지_않으면_이번_주기를_건너뛴다() {
        when(client.configured()).thenReturn(true);
        when(client.listEnabledSubscriptions()).thenThrow(new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE));
        scheduler.run();   // 예외가 밖으로 나오지 않아야 한다
        verify(interestRepository, never()).findAllByUserIdOrderByIdDesc(anyLong());
    }

    @Test
    void 정상_주기는_두_가지를_모두_돈다() {
        when(client.configured()).thenReturn(true);
        when(client.listEnabledSubscriptions()).thenReturn(List.of(subscriber(1, true)));
        when(interestRepository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of());
        when(cursorRepository.findById(NotificationCursor.NEW_MATCH)).thenReturn(Optional.empty());

        scheduler.run();

        verify(cursorRepository).save(any(NotificationCursor.class));
    }
}
