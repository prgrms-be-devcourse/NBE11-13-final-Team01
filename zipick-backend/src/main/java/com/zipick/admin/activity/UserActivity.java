package com.zipick.admin.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * 사용자 행동 기록 한 건: 화면 진입(PAGE_VIEW)·클릭(CLICK)·로그인(LOGIN).
 * 프론트가 묶어서 보내고(POST /api/v1/activity), 로그인은 서버가 직접 남긴다.
 * 비회원도 기록한다(userId null). 같은 브라우저는 anonymousId 로 묶는다.
 * IP 는 개인정보라 운영자 화면에서만 보이고, 90일 뒤 자동 삭제한다(ActivityService.cleanup).
 */
@Getter
@Entity
@Table(name = "user_activity")
public class UserActivity {

    public static final String PAGE_VIEW = "PAGE_VIEW";
    public static final String CLICK = "CLICK";
    public static final String LOGIN = "LOGIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_activity_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    /** 브라우저가 localStorage 에 두는 임의 id. 비회원 흐름과 로그인 전후를 잇는다 */
    @Column(name = "anonymous_id", length = 64)
    private String anonymousId;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    /** 화면 경로 (예: /map?mode=recommended, /houses/123) */
    @Column(name = "path", nullable = false, length = 500)
    private String path;

    /** 클릭한 요소 이름 (data-track, aria-label, 버튼 글자) */
    @Column(name = "label", length = 200)
    private String label;

    /** 클릭 대상 id 등 부가 정보 (예: housing:123, notice:45) */
    @Column(name = "target", length = 200)
    private String target;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserActivity() {
    }

    public UserActivity(Long userId, String anonymousId, String ip, String userAgent, String eventType,
                        String path, String label, String target, Instant occurredAt) {
        this.userId = userId;
        this.anonymousId = anonymousId;
        this.ip = ip;
        this.userAgent = userAgent;
        this.eventType = eventType;
        this.path = path;
        this.label = label;
        this.target = target;
        this.occurredAt = occurredAt;
        this.createdAt = Instant.now();
    }
}
