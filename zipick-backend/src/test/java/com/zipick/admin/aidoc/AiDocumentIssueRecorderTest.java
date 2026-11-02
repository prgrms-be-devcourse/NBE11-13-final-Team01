package com.zipick.admin.aidoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 문서 키당 한 행: 다시 실패하면 횟수만 오르고, 성공하면 지운다. 기록 실패는 수집을 멈추지 않는다 */
@ExtendWith(MockitoExtension.class)
class AiDocumentIssueRecorderTest {

    @Mock AiDocumentIssueRepository issues;

    @Test
    void 처음_실패하면_새_행을_만들고_긴_값은_자른다() {
        when(issues.findByDocumentKey("doc-1")).thenReturn(Optional.empty());
        AiDocumentIssueRecorder recorder = new AiDocumentIssueRecorder(issues);

        recorder.failed("doc-1", "LH", "t".repeat(600), "f".repeat(600), "incomplete", 10, 4, "d".repeat(3000));

        ArgumentCaptor<AiDocumentIssue> captor = ArgumentCaptor.forClass(AiDocumentIssue.class);
        verify(issues).save(captor.capture());
        AiDocumentIssue issue = captor.getValue();
        assertThat(issue.getDocumentKey()).isEqualTo("doc-1");
        assertThat(issue.getAgency()).isEqualTo("LH");
        assertThat(issue.getNoticeTitle()).hasSize(500);
        assertThat(issue.getFileName()).hasSize(500);
        assertThat(issue.getDetail()).hasSize(2000);
        assertThat(issue.getReason()).isEqualTo("incomplete");
        assertThat(issue.getExpectedUnits()).isEqualTo(10);
        assertThat(issue.getParsedUnits()).isEqualTo(4);
        assertThat(issue.getOccurrences()).isEqualTo(1);
        assertThat(issue.isAcknowledged()).isFalse();
    }

    @Test
    void 같은_문서가_다시_실패하면_기존_행의_횟수와_사유가_갱신된다() {
        AiDocumentIssue existing = new AiDocumentIssue("doc-1", "SH", "제목", "파일.pdf");
        existing.observe("incomplete", 5, 2, "first");
        when(issues.findByDocumentKey("doc-1")).thenReturn(Optional.of(existing));

        new AiDocumentIssueRecorder(issues).failed("doc-1", "SH", "제목", "파일.pdf", "exception", null, null, "boom");

        verify(issues).save(existing);
        assertThat(existing.getOccurrences()).isEqualTo(2);
        assertThat(existing.getReason()).isEqualTo("exception");
        assertThat(existing.getDetail()).isEqualTo("boom");
        assertThat(existing.getLastSeenAt()).isAfterOrEqualTo(existing.getFirstSeenAt());
    }

    @Test
    void 문서_키가_없으면_아무것도_하지_않는다() {
        AiDocumentIssueRecorder recorder = new AiDocumentIssueRecorder(issues);
        recorder.failed(null, "LH", null, null, "empty", null, null, null);
        recorder.failed("  ", "LH", null, null, "empty", null, null, null);
        recorder.succeeded(null);
        recorder.succeeded(" ");
        verify(issues, never()).save(any());
        verify(issues, never()).deleteByDocumentKey(anyString());
    }

    @Test
    void 성공하면_행을_지운다() {
        new AiDocumentIssueRecorder(issues).succeeded("doc-1");
        verify(issues).deleteByDocumentKey("doc-1");
    }

    @Test
    void 저장소_오류는_삼킨다() {
        when(issues.findByDocumentKey(anyString())).thenThrow(new RuntimeException("db down"));
        AiDocumentIssueRecorder recorder = new AiDocumentIssueRecorder(issues);
        recorder.failed("doc-1", "LH", null, null, "empty", null, null, null);

        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(issues).deleteByDocumentKey("doc-1");
        recorder.succeeded("doc-1");
    }

    @Test
    void 확인_표시와_다시_열기() {
        AiDocumentIssue issue = new AiDocumentIssue("doc-1", "LH", null, null);
        issue.acknowledge(9L, "확인함");
        assertThat(issue.isAcknowledged()).isTrue();
        assertThat(issue.getAcknowledgedBy()).isEqualTo(9L);
        assertThat(issue.getAdminNote()).isEqualTo("확인함");
        issue.reopen();
        assertThat(issue.isAcknowledged()).isFalse();
        assertThat(issue.getAcknowledgedBy()).isNull();
    }
}
