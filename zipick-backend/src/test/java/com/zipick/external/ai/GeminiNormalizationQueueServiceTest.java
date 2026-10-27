package com.zipick.external.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiNormalizationQueueServiceTest {

    @Test
    void API장애로_연기된_작업은_성공처리하지않고_RETRY로_남긴다() {
        GeminiDbNormalizationReviewer reviewer = mock(GeminiDbNormalizationReviewer.class);
        AiNormalizationJobService jobs = mock(AiNormalizationJobService.class);
        AiNormalizationPatchPersistenceService patches = mock(AiNormalizationPatchPersistenceService.class);
        GeminiNoticeNormalizationService service = new GeminiNoticeNormalizationService(reviewer, jobs, patches);
        AiNormalizationJob job = job(1L);
        GeminiNormalizationDocument document = document();
        when(reviewer.enabled()).thenReturn(true);
        when(jobs.findDue()).thenReturn(List.of(job));
        when(jobs.readDocument(job)).thenReturn(document);
        when(reviewer.review(List.of(document))).thenReturn(new GeminiNormalizationResult(
                List.of(), 1, 2, 0, 1000, List.of("LH:notice-1"), List.of("503 HIGH DEMAND")));

        service.normalizePending();

        verify(jobs).markRetry(eq(List.of(1L)), anyString());
        verify(jobs, never()).markSuccess(List.of(1L));
        verify(patches, never()).apply(org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void 수정사항이_없어도_검수를_마친_작업은_SUCCESS로_기록한다() {
        GeminiDbNormalizationReviewer reviewer = mock(GeminiDbNormalizationReviewer.class);
        AiNormalizationJobService jobs = mock(AiNormalizationJobService.class);
        AiNormalizationPatchPersistenceService patches = mock(AiNormalizationPatchPersistenceService.class);
        GeminiNoticeNormalizationService service = new GeminiNoticeNormalizationService(reviewer, jobs, patches);
        AiNormalizationJob job = job(2L);
        GeminiNormalizationDocument document = document();
        when(reviewer.enabled()).thenReturn(true);
        when(jobs.findDue()).thenReturn(List.of(job));
        when(jobs.readDocument(job)).thenReturn(document);
        when(reviewer.review(List.of(document))).thenReturn(new GeminiNormalizationResult(
                List.of(), 1, 1, 0, 1000, List.of(), List.of()));

        service.normalizePending();

        verify(patches).apply(org.mockito.ArgumentMatchers.anyMap(), eq(List.of()));
        verify(jobs).markSuccess(List.of(2L));
        verify(jobs, never()).markRetry(eq(List.of(2L)), anyString());
    }

    private AiNormalizationJob job(Long id) {
        AiNormalizationJob job = mock(AiNormalizationJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getSource()).thenReturn("LH");
        when(job.getDocumentId()).thenReturn("LH:notice-1");
        return job;
    }

    private GeminiNormalizationDocument document() {
        return new GeminiNormalizationDocument(
                "LH:notice-1", "LH", "공고",
                java.util.Map.of("rows", List.of(java.util.Map.of(
                        "rawPayload", java.util.Map.of(
                                "parser", GeminiPdfHousingExtractor.PROMPT_VERSION
                        )
                ))),
                java.util.Map.of());
    }
}
