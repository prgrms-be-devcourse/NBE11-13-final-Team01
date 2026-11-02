package com.zipick.admin.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.admin.AdminQueryRepository;
import com.zipick.admin.aidoc.AiDocumentIssueRepository;
import com.zipick.external.ai.AiNormalizationJobRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 수동 실행 요청: 한 번에 하나만, 대기 중인 것만 취소. 실행 기록 요약은 JSON 으로 오간다 */
@ExtendWith(MockitoExtension.class)
class AdminCollectionServiceTest {

    @Mock CollectionRunRepository runs;
    @Mock CollectionRequestRepository requests;
    @Mock AiDocumentIssueRepository issues;
    @Mock AiNormalizationJobRepository jobs;
    @Mock AdminQueryRepository query;
    AdminCollectionService service;

    @BeforeEach
    void setUp() {
        service = new AdminCollectionService(runs, requests, issues, jobs, query);
    }

    @Test
    void 대기_또는_실행_중인_요청이_있으면_새_요청을_거절한다() {
        when(requests.existsByStatusIn(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.request(1L, CollectionJobType.FULL, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COLLECTION_ALREADY_REQUESTED);
        verify(requests, never()).save(any());
    }

    @Test
    void 요청은_PENDING_으로_저장된다() {
        when(requests.existsByStatusIn(anyCollection())).thenReturn(false);
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.request(7L, CollectionJobType.AI, "확인용");

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.jobType()).isEqualTo(CollectionJobType.AI);
        assertThat(res.requestedBy()).isEqualTo(7L);
        assertThat(res.note()).isEqualTo("확인용");
    }

    @Test
    void 이미_시작된_요청은_취소할_수_없다() {
        CollectionRequest running = new CollectionRequest(CollectionJobType.BASIC, 1L, null);
        // 실행기(쉘)가 RUNNING 으로 바꾼 상태를 흉내 낸다
        org.springframework.test.util.ReflectionTestUtils.setField(running, "status", CollectionRequest.Status.RUNNING);
        when(requests.findById(3L)).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> service.cancel(3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DATA_CONFLICT);
    }

    @Test
    void 실행_기록_요약은_JSON_에서_맵으로_풀린다() {
        CollectionRun run = new CollectionRun(CollectionJobType.BASIC, "CRON", null, Instant.parse("2026-10-29T00:30:00Z"));
        run.succeed("{\"createdNotices\":3,\"infra\":{\"saved\":21}}", Instant.parse("2026-10-29T00:35:00Z"));
        when(runs.findFirstByJobTypeOrderByStartedAtDesc(CollectionJobType.BASIC)).thenReturn(Optional.of(run));
        when(runs.findFirstByJobTypeOrderByStartedAtDesc(CollectionJobType.AI)).thenReturn(Optional.empty());
        when(runs.findFirstByJobTypeOrderByStartedAtDesc(CollectionJobType.INFRA)).thenReturn(Optional.empty());
        when(runs.findAllByStatus(CollectionRun.Status.RUNNING)).thenReturn(List.of());
        when(requests.findAllByStatusIn(anyCollection())).thenReturn(List.of());

        var overview = service.overview();

        var basic = overview.lastRuns().get("BASIC");
        assertThat(basic.durationSeconds()).isEqualTo(300L);
        assertThat(basic.summary()).containsEntry("createdNotices", 3);
        assertThat(basic.summary().get("infra")).isInstanceOf(java.util.Map.class);
        assertThat(overview.lastRuns()).doesNotContainKey("AI");
    }
}
