package com.zipick.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영자 화면용 집계·목록 조회. 여러 도메인 테이블을 한 번에 세는 질의라 각 도메인 리포지토리에 흩뿌리지 않고 여기 모은다.
 * 읽기 전용이고, 결과는 수집 현황·회원 목록 화면에서만 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class AdminQueryRepository {

    private final EntityManager em;

    public long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    public long countHousings() { return count("select count(*) from housing"); }

    public long countActiveHousings() { return count("select count(*) from housing where active"); }

    public long countActiveHousingsWithoutCoordinates() {
        return count("select count(*) from housing where active and (latitude is null or longitude is null)");
    }

    public long countInfraSnapshots() { return count("select count(*) from housing_infra_snapshot"); }

    /** 모집 중(공고 예정·접수 중) 주택 가운데 좌표는 있는데 인프라가 아직 없는 수 */
    public long countOpenHousingsWithoutInfra() {
        return count("""
                select count(*) from housing h
                join notice n on n.notice_id = h.notice_id
                where h.active and h.latitude is not null and h.longitude is not null
                  and n.status in ('UPCOMING','OPEN')
                  and not exists (select 1 from housing_infra_snapshot s where s.housing_id = h.housing_id)
                """);
    }

    public long countOpenHousings() {
        return count("""
                select count(*) from housing h
                join notice n on n.notice_id = h.notice_id
                where h.active and n.status in ('UPCOMING','OPEN')
                """);
    }

    public Map<String, Long> countNoticesByStatus() {
        return groupCount("select status, count(*) from notice group by status order by status");
    }

    public Map<String, Long> countNormalizationJobsByStatus() {
        return groupCount("select normalization_status, count(*) from ai_normalization_job group by normalization_status order by normalization_status");
    }

    public long countUsers() { return count("select count(*) from users"); }

    public long countUsersCreatedAfter(LocalDateTime since) {
        return ((Number) em.createNativeQuery("select count(*) from users where created_at >= :since")
                .setParameter("since", since).getSingleResult()).longValue();
    }

    /** 기본 정보와 희망 주거조건을 모두 입력한 회원 수 (온보딩 완료) */
    public long countOnboardedUsers() {
        return count("""
                select count(*) from users u
                where exists (select 1 from user_profiles p where p.user_id = u.user_id)
                  and exists (select 1 from user_preferences f where f.user_id = u.user_id)
                """);
    }

    public long countUsersWithNotificationEnabled() {
        return count("select count(*) from notification_settings where enabled");
    }

    public long countMarketingConsented() {
        return count("select count(*) from notification_settings where marketing_consent");
    }

    public long countInterestNotices() { return count("select count(*) from interest_notices"); }

    @SuppressWarnings("unchecked")
    public List<String> allPreferenceRegions() {
        return em.createNativeQuery("select regions from user_preferences where regions is not null").getResultList();
    }

    /** 회원 목록 한 행. 온보딩 여부는 profile/preference 행 존재로 판단한다 */
    public record UserRow(Long userId, String email, String role, LocalDateTime createdAt,
                          boolean hasProfile, boolean hasPreference, boolean notificationEnabled, long interestCount) {
    }

    public long countUsersMatching(String q) {
        return ((Number) em.createNativeQuery("select count(*) from users u where u.email ilike ('%' || :q || '%')")
                .setParameter("q", q == null ? "" : q).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    public List<UserRow> findUsers(String q, int offset, int limit) {
        Query query = em.createNativeQuery("""
                select u.user_id, u.email, u.role, u.created_at,
                       exists (select 1 from user_profiles p where p.user_id = u.user_id) as has_profile,
                       exists (select 1 from user_preferences f where f.user_id = u.user_id) as has_preference,
                       coalesce((select s.enabled from notification_settings s where s.user_id = u.user_id), false) as notification_enabled,
                       (select count(*) from interest_notices i where i.user_id = u.user_id) as interest_count
                from users u
                where u.email ilike ('%' || :q || '%')
                order by u.user_id desc
                offset :offset limit :limit
                """);
        query.setParameter("q", q == null ? "" : q);
        query.setParameter("offset", offset);
        query.setParameter("limit", limit);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(r -> new UserRow(
                ((Number) r[0]).longValue(),
                (String) r[1],
                (String) r[2],
                toLocalDateTime(r[3]),
                Boolean.TRUE.equals(r[4]),
                Boolean.TRUE.equals(r[5]),
                Boolean.TRUE.equals(r[6]),
                ((Number) r[7]).longValue()
        )).toList();
    }

    /** 드라이버·Hibernate 버전에 따라 Timestamp 또는 LocalDateTime 으로 온다 */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof Timestamp t) return t.toLocalDateTime();
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> groupCount(String sql) {
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) result.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        return result;
    }
}
