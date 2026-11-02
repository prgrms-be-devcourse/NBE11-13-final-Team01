package com.zipick.admin.activity;

import com.zipick.admin.dto.PageResponse;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자 행동 기록. 쓰기(프론트 배치·로그인)와 운영자 조회를 함께 둔다.
 * 기록은 서비스 흐름에 영향을 주면 안 되므로 쓰기 실패는 로그만 남기고 삼킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityService {

    /** 한 요청에 받는 최대 이벤트 수. 그 이상은 잘라 버린다(악의적 폭주 방지) */
    public static final int MAX_BATCH = 50;
    /** 보관 기간. IP 는 개인정보라 오래 두지 않는다 */
    private static final Duration RETENTION = Duration.ofDays(90);

    private final UserActivityRepository activities;

    public record EventIn(String type, String path, String label, String target, Instant at) {}

    @Transactional
    public int record(Long userId, String anonymousId, HttpServletRequest request, List<EventIn> events) {
        if (events == null || events.isEmpty()) return 0;
        String ip = clientIp(request);
        String ua = trim(request.getHeader("User-Agent"), 300);
        String anon = trim(anonymousId, 64);
        Instant now = Instant.now();
        List<UserActivity> rows = new ArrayList<>();
        for (EventIn e : events.subList(0, Math.min(events.size(), MAX_BATCH))) {
            if (e == null || e.path() == null || e.path().isBlank()) continue;
            String type = UserActivity.CLICK.equalsIgnoreCase(e.type()) ? UserActivity.CLICK : UserActivity.PAGE_VIEW;
            // 클라이언트 시각은 믿지 않는다: 미래거나 하루 넘게 과거면 서버 시각으로
            Instant at = e.at() == null || e.at().isAfter(now.plusSeconds(60)) || e.at().isBefore(now.minus(Duration.ofDays(1))) ? now : e.at();
            rows.add(new UserActivity(userId, anon, ip, ua, type, trim(e.path(), 500), trim(e.label(), 200), trim(e.target(), 200), at));
        }
        try {
            activities.saveAll(rows);
        } catch (RuntimeException ex) {
            log.warn("행동 기록 저장 실패: {}", ex.toString());
            return 0;
        }
        return rows.size();
    }

    /** 로그인 성공을 서버가 직접 남긴다 (IP·시각이 가장 믿을 만한 기록) */
    @Transactional
    public void recordLogin(Long userId, HttpServletRequest request, String path) {
        try {
            activities.save(new UserActivity(userId, trim(request.getHeader("X-Zipick-Anon"), 64), clientIp(request),
                    trim(request.getHeader("User-Agent"), 300), UserActivity.LOGIN, path, "로그인", null, Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("로그인 기록 실패: {}", ex.toString());
        }
    }

    /** Caddy 가 앞에 있으므로 X-Forwarded-For 의 첫 값이 실제 클라이언트. 없으면 소켓 주소 */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) return trim(first, 64);
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return trim(real.trim(), 64);
        return trim(request.getRemoteAddr(), 64);
    }

    // ---- 운영자 조회 ----

    public record ActivityRow(Long id, Long userId, String anonymousId, String ip, String userAgent, String eventType,
                              String path, String label, String target, Instant occurredAt) {}

    public record ActorRow(Long userId, String anonymousId, String lastIp, Instant lastSeen, Instant firstSeen,
                           long events, long pageViews, long clicks, String lastPath) {}

    public record UserSummary(Instant lastSeen, long events) {}

    public PageResponse<ActivityRow> events(Long userId, String anonymousId, String ip, String eventType, String pathLike,
                                            Instant since, int page, int size) {
        Specification<UserActivity> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (userId != null) ps.add(cb.equal(root.get("userId"), userId));
            if (anonymousId != null && !anonymousId.isBlank()) ps.add(cb.equal(root.get("anonymousId"), anonymousId.trim()));
            if (ip != null && !ip.isBlank()) ps.add(cb.equal(root.get("ip"), ip.trim()));
            if (eventType != null && !eventType.isBlank()) ps.add(cb.equal(root.get("eventType"), eventType.trim().toUpperCase()));
            if (pathLike != null && !pathLike.isBlank()) ps.add(cb.like(root.get("path"), "%" + pathLike.trim() + "%"));
            if (since != null) ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), since));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        return PageResponse.of(activities.findAll(spec, pageable), ActivityService::toRow);
    }

    /** 한 사용자(또는 브라우저)의 행동을 시간순으로: "어디까지 눌렀는지" */
    public List<ActivityRow> trail(Long userId, String anonymousId, int limit) {
        List<UserActivity> list;
        if (userId != null) {
            list = activities.findAllByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, limit)).getContent();
        } else if (anonymousId != null && !anonymousId.isBlank()) {
            list = activities.findTop200ByAnonymousIdOrderByOccurredAtDesc(anonymousId.trim());
        } else {
            return List.of();
        }
        List<ActivityRow> rows = new ArrayList<>(list.stream().map(ActivityService::toRow).toList());
        java.util.Collections.reverse(rows);
        return rows;
    }

    public PageResponse<ActorRow> recentActors(Duration window, int page, int size) {
        Instant since = Instant.now().minus(window);
        long total = activities.countRecentActors(since);
        List<ActorRow> rows = activities.recentActors(since, size, (page - 1) * size).stream().map(r -> new ActorRow(
                r[1] == null ? null : ((Number) r[1]).longValue(),
                (String) r[2],
                (String) r[3],
                toInstant(r[4]),
                toInstant(r[5]),
                ((Number) r[6]).longValue(),
                ((Number) r[7]).longValue(),
                ((Number) r[8]).longValue(),
                (String) r[9]
        )).toList();
        return new PageResponse<>(page, size, total, (int) Math.ceil(total / (double) size), rows);
    }

    public Map<Long, UserSummary> summaries(List<Long> userIds) {
        Map<Long, UserSummary> out = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return out;
        for (Object[] r : activities.summarizeByUserIds(userIds)) {
            out.put(((Number) r[0]).longValue(), new UserSummary(toInstant(r[1]), ((Number) r[2]).longValue()));
        }
        return out;
    }

    /** 90일 지난 기록 삭제. 매일 04:20 (웹 서버 스케줄러) */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void cleanup() {
        try {
            int n = activities.deleteByOccurredAtBefore(Instant.now().minus(RETENTION));
            if (n > 0) log.info("행동 기록 {}건 정리 (90일 경과)", n);
        } catch (RuntimeException ex) {
            log.warn("행동 기록 정리 실패: {}", ex.toString());
        }
    }

    private static ActivityRow toRow(UserActivity a) {
        return new ActivityRow(a.getId(), a.getUserId(), a.getAnonymousId(), a.getIp(), a.getUserAgent(), a.getEventType(),
                a.getPath(), a.getLabel(), a.getTarget(), a.getOccurredAt());
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof java.time.LocalDateTime l) return l.atZone(java.time.ZoneId.systemDefault()).toInstant();
        return null;
    }

    private static String trim(String v, int max) {
        if (v == null) return null;
        String s = v.strip();
        if (s.isEmpty()) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
