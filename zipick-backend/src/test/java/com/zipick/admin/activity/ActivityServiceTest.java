package com.zipick.admin.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;

/** 행동 기록: 클라이언트 값은 믿지 않고 자르며, 저장 실패는 서비스 흐름을 막지 않는다 */
@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock UserActivityRepository activities;
    ActivityService service;

    @BeforeEach
    void setUp() {
        service = new ActivityService(activities);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("User-Agent", "ua");
        return req;
    }

    @Test
    void 빈_배치는_저장하지_않는다() {
        assertThat(service.record(1L, "anon", request(), null)).isZero();
        assertThat(service.record(1L, "anon", request(), List.of())).isZero();
        verify(activities, never()).saveAll(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 경로_없는_이벤트는_거르고_시각이_수상하면_서버_시각으로_바꾼다() {
        Instant now = Instant.now();
        Instant future = now.plus(Duration.ofHours(1));
        Instant sane = now.minusSeconds(30);
        List<ActivityService.EventIn> events = Arrays.asList(
                new ActivityService.EventIn("click", "/map", "버튼", "housing:1", sane),
                new ActivityService.EventIn("page_view", "/landing", null, null, future),
                new ActivityService.EventIn("anything", "   ", null, null, null),
                null
        );

        int saved = service.record(7L, "anon-1", request(), events);

        assertThat(saved).isEqualTo(2);
        ArgumentCaptor<List<UserActivity>> captor = ArgumentCaptor.forClass(List.class);
        verify(activities).saveAll(captor.capture());
        List<UserActivity> rows = captor.getValue();
        assertThat(rows.get(0).getEventType()).isEqualTo(UserActivity.CLICK);
        assertThat(rows.get(0).getOccurredAt()).isEqualTo(sane);
        assertThat(rows.get(0).getUserId()).isEqualTo(7L);
        assertThat(rows.get(0).getIp()).isEqualTo("10.0.0.1");
        assertThat(rows.get(1).getEventType()).isEqualTo(UserActivity.PAGE_VIEW);
        assertThat(rows.get(1).getOccurredAt()).isAfterOrEqualTo(now);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 배치는_최대_50건까지만_받고_긴_값은_자른다() {
        String longPath = "/".repeat(600);
        List<ActivityService.EventIn> events = Collections.nCopies(80,
                new ActivityService.EventIn("CLICK", longPath, "x".repeat(300), null, null));

        int saved = service.record(null, "a".repeat(100), request(), events);

        assertThat(saved).isEqualTo(ActivityService.MAX_BATCH);
        ArgumentCaptor<List<UserActivity>> captor = ArgumentCaptor.forClass(List.class);
        verify(activities).saveAll(captor.capture());
        UserActivity first = captor.getValue().get(0);
        assertThat(first.getPath()).hasSize(500);
        assertThat(first.getLabel()).hasSize(200);
        assertThat(first.getAnonymousId()).hasSize(64);
        assertThat(first.getUserId()).isNull();
    }

    @Test
    void 저장_실패는_삼키고_0을_돌려준다() {
        when(activities.saveAll(anyList())).thenThrow(new RuntimeException("db down"));
        int saved = service.record(1L, null, request(),
                List.of(new ActivityService.EventIn("CLICK", "/map", null, null, null)));
        assertThat(saved).isZero();
    }

    @Test
    void 로그인_기록은_서버가_남기고_실패해도_예외를_내지_않는다() {
        MockHttpServletRequest req = request();
        req.addHeader("X-Zipick-Anon", "anon-9");
        service.recordLogin(3L, req, "/api/auth/login");
        ArgumentCaptor<UserActivity> captor = ArgumentCaptor.forClass(UserActivity.class);
        verify(activities).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(UserActivity.LOGIN);
        assertThat(captor.getValue().getAnonymousId()).isEqualTo("anon-9");
        assertThat(captor.getValue().getLabel()).isEqualTo("로그인");

        when(activities.save(any())).thenThrow(new RuntimeException("db down"));
        service.recordLogin(3L, req, "/api/auth/login");
    }

    @Test
    void 클라이언트_IP는_XFF_첫_값_그다음_XRealIP_그다음_소켓_주소() {
        MockHttpServletRequest req = request();
        req.addHeader("X-Forwarded-For", " 1.2.3.4 , 5.6.7.8");
        assertThat(ActivityService.clientIp(req)).isEqualTo("1.2.3.4");

        MockHttpServletRequest real = request();
        real.addHeader("X-Real-IP", "9.9.9.9");
        assertThat(ActivityService.clientIp(real)).isEqualTo("9.9.9.9");

        assertThat(ActivityService.clientIp(request())).isEqualTo("10.0.0.1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 이벤트_조회는_최근순_페이지로_돌려준다() {
        UserActivity a = new UserActivity(1L, "anon", "1.1.1.1", "ua", UserActivity.CLICK, "/map", "버튼", "t", Instant.now());
        when(activities.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a), PageRequest.of(0, 20), 1));

        var page = service.events(1L, "anon", "1.1.1.1", "click", "map", Instant.EPOCH, 1, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).path()).isEqualTo("/map");
        assertThat(page.items().get(0).eventType()).isEqualTo(UserActivity.CLICK);
    }

    @Test
    void 이동_경로는_오래된_것부터_시간순이다() {
        Instant t1 = Instant.parse("2026-11-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-11-01T00:01:00Z");
        UserActivity later = new UserActivity(1L, null, null, null, UserActivity.CLICK, "/b", null, null, t2);
        UserActivity earlier = new UserActivity(1L, null, null, null, UserActivity.PAGE_VIEW, "/a", null, null, t1);
        when(activities.findAllByUserIdOrderByOccurredAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(later, earlier)));

        List<ActivityService.ActivityRow> trail = service.trail(1L, null, 100);

        assertThat(trail).extracting(ActivityService.ActivityRow::path).containsExactly("/a", "/b");
    }

    @Test
    void 이동_경로는_비회원이면_브라우저_id로_찾고_둘_다_없으면_비어_있다() {
        when(activities.findTop200ByAnonymousIdOrderByOccurredAtDesc("anon"))
                .thenReturn(List.of(new UserActivity(null, "anon", null, null, UserActivity.PAGE_VIEW, "/x", null, null, Instant.now())));
        assertThat(service.trail(null, " anon ", 10)).hasSize(1);
        assertThat(service.trail(null, "  ", 10)).isEmpty();
    }

    @Test
    void 최근_활동_사용자는_네이티브_행을_읽어_만든다() {
        Instant seen = Instant.parse("2026-11-01T09:00:00Z");
        Object[] row = {"7", 7, "anon", "1.1.1.1", java.sql.Timestamp.from(seen),
                java.time.OffsetDateTime.ofInstant(seen, java.time.ZoneOffset.UTC), 5L, 3L, 2L, "/map"};
        when(activities.countRecentActors(any())).thenReturn(1L);
        when(activities.recentActors(any(), any(Integer.class), any(Integer.class))).thenReturn(List.<Object[]>of(row));

        var page = service.recentActors(Duration.ofDays(7), 1, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        ActivityService.ActorRow actor = page.items().get(0);
        assertThat(actor.userId()).isEqualTo(7L);
        assertThat(actor.lastSeen()).isEqualTo(seen);
        assertThat(actor.firstSeen()).isEqualTo(seen);
        assertThat(actor.events()).isEqualTo(5);
        assertThat(actor.lastPath()).isEqualTo("/map");
    }

    @Test
    void 사용자별_요약은_빈_입력이면_조회하지_않는다() {
        assertThat(service.summaries(null)).isEmpty();
        assertThat(service.summaries(List.of())).isEmpty();
        verify(activities, never()).summarizeByUserIds(anyList());

        Instant seen = Instant.parse("2026-11-01T09:00:00Z");
        when(activities.summarizeByUserIds(List.of(1L))).thenReturn(List.<Object[]>of(new Object[]{1L, seen, 4L}));
        Map<Long, ActivityService.UserSummary> out = service.summaries(List.of(1L));
        assertThat(out.get(1L).lastSeen()).isEqualTo(seen);
        assertThat(out.get(1L).events()).isEqualTo(4);
    }

    @Test
    void 정리는_90일_전_기록을_지우고_실패해도_예외를_내지_않는다() {
        when(activities.deleteByOccurredAtBefore(any())).thenReturn(3);
        service.cleanup();
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(activities).deleteByOccurredAtBefore(captor.capture());
        assertThat(captor.getValue()).isBefore(Instant.now().minus(Duration.ofDays(89)));

        when(activities.deleteByOccurredAtBefore(any())).thenThrow(new RuntimeException("db down"));
        service.cleanup();
    }
}
