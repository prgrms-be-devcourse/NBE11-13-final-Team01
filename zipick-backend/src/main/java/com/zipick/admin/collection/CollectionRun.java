package com.zipick.admin.collection;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 수집기 프로세스 한 번의 실행 기록. 지금까지는 로그와 .prom 파일에만 남던 결과를 DB 에 남겨 운영자 화면에서 본다.
 * 수집기 진입점(batch/*Application)이 시작할 때 RUNNING 으로 넣고 끝날 때 SUCCESS/FAILED 로 바꾼다.
 */
@Getter
@Entity
@Table(name = "collection_run")
public class CollectionRun extends BaseTimeEntity {

    public enum Status { RUNNING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "collection_run_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private CollectionJobType jobType;

    /** CRON(일일 스케줄) / ADMIN(운영자 화면 요청) / MANUAL(서버에서 직접 실행) */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String trigger;

    /** 운영자 요청으로 실행된 경우 collection_request.id */
    @Column(name = "request_id")
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** 결과 요약(신규 공고 수, 인프라 저장 수 등). 종류마다 키가 달라 jsonb 로 둔다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private String summary;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    protected CollectionRun() {
    }

    public CollectionRun(CollectionJobType jobType, String trigger, Long requestId, Instant startedAt) {
        this.jobType = jobType;
        this.trigger = trigger;
        this.requestId = requestId;
        this.status = Status.RUNNING;
        this.startedAt = startedAt;
    }

    public void succeed(String summaryJson, Instant endedAt) {
        this.status = Status.SUCCESS;
        this.summary = summaryJson;
        this.endedAt = endedAt;
    }

    public void fail(String errorMessage, String summaryJson, Instant endedAt) {
        this.status = Status.FAILED;
        this.errorMessage = errorMessage;
        this.summary = summaryJson;
        this.endedAt = endedAt;
    }

    /** 프로세스가 죽어 RUNNING 으로 남은 행을 운영자 화면에서 정리할 때 */
    public void abandon(Instant endedAt) {
        this.status = Status.FAILED;
        this.errorMessage = "프로세스가 결과를 남기지 못하고 끝났습니다 (운영자 정리)";
        this.endedAt = endedAt;
    }
}
