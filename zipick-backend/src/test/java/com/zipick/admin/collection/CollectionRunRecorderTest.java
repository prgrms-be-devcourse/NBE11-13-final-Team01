package com.zipick.admin.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 실행 기록은 부가 정보: 트리거·요청 id 를 담고, 기록에 실패해도 수집은 계속된다 */
@ExtendWith(MockitoExtension.class)
class CollectionRunRecorderTest {

    @Mock CollectionRunRepository runs;

    @Test
    void 기본은_CRON이고_요청_id가_없다() {
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CollectionRunRecorder recorder = new CollectionRunRecorder(runs, " ", "");

        CollectionRun run = recorder.start(CollectionJobType.BASIC);

        assertThat(run.getTrigger()).isEqualTo("CRON");
        assertThat(run.getRequestId()).isNull();
        assertThat(run.getStatus()).isEqualTo(CollectionRun.Status.RUNNING);
        assertThat(run.getJobType()).isEqualTo(CollectionJobType.BASIC);
    }

    @Test
    void 운영자_요청이면_ADMIN과_요청_id를_남긴다() {
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CollectionRun run = new CollectionRunRecorder(runs, "admin", " 42 ").start(CollectionJobType.AI);
        assertThat(run.getTrigger()).isEqualTo("ADMIN");
        assertThat(run.getRequestId()).isEqualTo(42L);
    }

    @Test
    void 시작_기록에_실패하면_null을_돌려주고_이후_호출은_무시된다() {
        when(runs.save(any())).thenThrow(new RuntimeException("db down"));
        CollectionRunRecorder recorder = new CollectionRunRecorder(runs, null, null);

        CollectionRun run = recorder.start(CollectionJobType.INFRA);

        assertThat(run).isNull();
        recorder.success(null, Map.of("a", 1));
        recorder.fail(null, new RuntimeException("x"), null);
        verify(runs, never()).findById(any());
    }

    @Test
    void 성공과_실패는_같은_행을_다시_읽어_결과를_적는다() {
        CollectionRun run = new CollectionRun(CollectionJobType.BASIC, "CRON", null, java.time.Instant.now());
        when(runs.findById(any())).thenReturn(Optional.of(run));
        CollectionRunRecorder recorder = new CollectionRunRecorder(runs, null, null);

        Map<String, Object> summary = CollectionRunRecorder.summary();
        summary.put("notices", 83);
        recorder.success(run, summary);
        assertThat(run.getStatus()).isEqualTo(CollectionRun.Status.SUCCESS);
        assertThat(run.getSummary()).contains("\"notices\":83");
        assertThat(run.getEndedAt()).isNotNull();

        recorder.fail(run, new IllegalStateException("boom"), Map.of());
        assertThat(run.getStatus()).isEqualTo(CollectionRun.Status.FAILED);
        assertThat(run.getErrorMessage()).contains("boom");
        assertThat(run.getSummary()).isNull();

        recorder.fail(run, null, null);
        assertThat(run.getErrorMessage()).isEqualTo("알 수 없는 오류");
    }

    @Test
    void 결과_기록_실패는_삼킨다() {
        CollectionRun run = new CollectionRun(CollectionJobType.BASIC, "CRON", null, java.time.Instant.now());
        when(runs.findById(any())).thenThrow(new RuntimeException("db down"));
        CollectionRunRecorder recorder = new CollectionRunRecorder(runs, null, null);
        recorder.success(run, null);
        recorder.fail(run, new RuntimeException("x"), null);
    }

    @Test
    void 버려진_실행은_실패로_닫힌다() {
        CollectionRun run = new CollectionRun(CollectionJobType.AI, "CRON", null, java.time.Instant.now());
        run.abandon(java.time.Instant.now());
        assertThat(run.getStatus()).isEqualTo(CollectionRun.Status.FAILED);
        assertThat(run.getErrorMessage()).contains("운영자 정리");
    }
}
