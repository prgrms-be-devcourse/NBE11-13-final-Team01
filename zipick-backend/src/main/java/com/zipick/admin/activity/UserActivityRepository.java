package com.zipick.admin.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserActivityRepository extends JpaRepository<UserActivity, Long>, JpaSpecificationExecutor<UserActivity> {

    Page<UserActivity> findAllByUserIdOrderByOccurredAtDesc(Long userId, Pageable pageable);

    List<UserActivity> findTop200ByAnonymousIdOrderByOccurredAtDesc(String anonymousId);

    /** 사용자별 마지막 활동 (최근순). 회원 목록·상세의 "마지막 IP·시각" */
    @Query("""
            select a.userId, max(a.occurredAt), count(a)
            from UserActivity a
            where a.userId in :userIds
            group by a.userId
            """)
    List<Object[]> summarizeByUserIds(@Param("userIds") List<Long> userIds);

    /** 최근 활동 사용자/브라우저 (회원이면 userId, 아니면 anonymousId 로 묶음) */
    @Query(value = """
            select coalesce(a.user_id::text, 'anon:' || a.anonymous_id) as actor,
                   max(a.user_id) as user_id,
                   max(a.anonymous_id) as anonymous_id,
                   (array_agg(a.ip order by a.occurred_at desc))[1] as last_ip,
                   max(a.occurred_at) as last_seen,
                   min(a.occurred_at) as first_seen,
                   count(*) as events,
                   count(*) filter (where a.event_type = 'PAGE_VIEW') as page_views,
                   count(*) filter (where a.event_type = 'CLICK') as clicks,
                   (array_agg(a.path order by a.occurred_at desc))[1] as last_path
            from user_activity a
            where a.occurred_at >= :since
              and (a.user_id is not null or a.anonymous_id is not null)
            group by actor
            order by last_seen desc
            limit :limit offset :offset
            """, nativeQuery = true)
    List<Object[]> recentActors(@Param("since") Instant since, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            select count(*) from (
              select 1 from user_activity a
              where a.occurred_at >= :since and (a.user_id is not null or a.anonymous_id is not null)
              group by coalesce(a.user_id::text, 'anon:' || a.anonymous_id)
            ) t
            """, nativeQuery = true)
    long countRecentActors(@Param("since") Instant since);

    @Modifying
    @Query("delete from UserActivity a where a.occurredAt < :before")
    int deleteByOccurredAtBefore(@Param("before") Instant before);
}
