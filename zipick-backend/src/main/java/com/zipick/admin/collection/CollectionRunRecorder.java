package com.zipick.admin.collection;

import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 수집기 진입점이 실행 기록(collection_run)을 남기는 창구.
 *
 * 수집 자체가 길고 기록은 부가 정보라, 기록에 실패해도 수집은 계속한다(여기서 예외를 삼킨다).
 * 각 메서드가 REQUIRES_NEW 로 바로 커밋되므로 실행 도중에도 운영자 화면에 RUNNING 으로 보인다.
 */
@Slf4j
@Service
public class CollectionRunRecorder {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final CollectionRunRepository runs;
    private final String trigger;
    private final Long requestId;

    public CollectionRunRecorder(
            CollectionRunRepository runs,
            // 운영자 요청 실행이면 run-requested-collection.sh 가 ADMIN 과 요청 id 를 넘긴다. 기본은 CRON
            @Value("${zipick.collection.trigger:CRON}") String trigger,
            @Value("${zipick.collection.request-id:}") String requestId
    ) {
        this.runs = runs;
        this.trigger = trigger == null || trigger.isBlank() ? "CRON" : trigger.trim().toUpperCase();
        this.requestId = requestId == null || requestId.isBlank() ? null : Long.valueOf(requestId.trim());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CollectionRun start(CollectionJobType jobType) {
        try {
            return runs.save(new CollectionRun(jobType, trigger, requestId, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("수집 실행 기록을 시작하지 못했습니다: {}", e.toString());
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(CollectionRun run, Map<String, ?> summary) {
        if (run == null) return;
        try {
            runs.findById(run.getId()).ifPresent(r -> r.succeed(toJson(summary), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("수집 실행 결과를 기록하지 못했습니다: {}", e.toString());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(CollectionRun run, Throwable error, Map<String, ?> summary) {
        if (run == null) return;
        try {
            String message = error == null ? "알 수 없는 오류" : error.toString();
            runs.findById(run.getId()).ifPresent(r -> r.fail(message, toJson(summary), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("수집 실행 실패를 기록하지 못했습니다: {}", e.toString());
        }
    }

    /** 요약을 차곡차곡 쌓을 때 쓰는 순서 보존 맵 */
    public static Map<String, Object> summary() {
        return new LinkedHashMap<>();
    }

    private String toJson(Map<String, ?> summary) {
        if (summary == null || summary.isEmpty()) return null;
        try {
            return JSON_MAPPER.writeValueAsString(summary);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
