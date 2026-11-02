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

import java.time.Instant;

/**
 * 운영자 화면의 "수집 실행" 요청. 웹 서버는 수집을 직접 돌리지 않고 이 행만 남긴다.
 * 앱 서버의 cron(run-requested-collection.sh, 1분)이 PENDING 을 집어 collector 컨테이너로 실행하고 상태를 갱신한다.
 */
@Getter
@Entity
@Table(name = "collection_request")
public class CollectionRequest extends BaseTimeEntity {

    public enum Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "collection_request_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private CollectionJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 실행기(쉘)가 남기는 종료 코드/메시지 */
    @Column(name = "result_message", length = 500)
    private String resultMessage;

    protected CollectionRequest() {
    }

    public CollectionRequest(CollectionJobType jobType, Long requestedBy, String note) {
        this.jobType = jobType;
        this.requestedBy = requestedBy;
        this.note = note;
        this.status = Status.PENDING;
    }

    public boolean isOpen() {
        return status == Status.PENDING || status == Status.RUNNING;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.finishedAt = Instant.now();
    }
}
