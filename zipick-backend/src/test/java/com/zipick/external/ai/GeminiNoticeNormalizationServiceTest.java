package com.zipick.external.ai;

import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.service.NoticeSyncCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiNoticeNormalizationServiceTest {

    @Test
    void 기존_자료와_합쳐진_Lite_결과도_정규화_대기열에_등록한다() {
        var reviewer = mock(GeminiDbNormalizationReviewer.class);
        var jobs = mock(AiNormalizationJobService.class);
        when(reviewer.enabled()).thenReturn(true);
        var service = new GeminiNoticeNormalizationService(reviewer, jobs, mock(AiNormalizationPatchPersistenceService.class));
        String raw = "{\"primary\":{},\"supplement\":{\"parser\":\"" + GeminiPdfHousingExtractor.PROMPT_VERSION + "\"}}";
        service.enqueue(List.of(commandWithRaw(raw)));
        verify(jobs).enqueue(anyList());
    }

    @Test
    void 검수패치를_DB후보와_추가속성에_적용한다() {
        GeminiDbNormalizationReviewer reviewer = mock(GeminiDbNormalizationReviewer.class);
        when(reviewer.enabled()).thenReturn(true);
        when(reviewer.review(anyList())).thenReturn(new GeminiNormalizationResult(List.of(
                correction("SUPPLY", "monthlyRent", null, null, 350_000L),
                correction("ATTRIBUTE", "additionalAttributes", "성별용도", "남여공용", null)
        ), 1, 1, 0, 5_000, List.of(), List.of()));
        GeminiNoticeNormalizationService service = new GeminiNoticeNormalizationService(
                reviewer, mock(AiNormalizationJobService.class),
                mock(AiNormalizationPatchPersistenceService.class));

        NoticeSyncCommand normalized = service.normalize(List.of(command())).getFirst();
        NoticeSupplyResponse supply = normalized.notice().houses().getFirst().supplies().getFirst();

        assertThat(supply.monthlyRent()).isEqualTo(350_000L);
        assertThat(supply.rawPayload()).contains("성별용도", "남여공용", "DB_NORMALIZATION_PATCH_V2_FIELD_WHITELIST");
    }

    @Test
    void V14_불완전_추출결과는_3점8_정규화에_전달하지_않는다() {
        GeminiDbNormalizationReviewer reviewer = mock(GeminiDbNormalizationReviewer.class);
        when(reviewer.enabled()).thenReturn(true);
        GeminiNoticeNormalizationService service = new GeminiNoticeNormalizationService(
                reviewer, mock(AiNormalizationJobService.class),
                mock(AiNormalizationPatchPersistenceService.class));

        List<NoticeSyncCommand> result = service.normalize(List.of(command(
                "DIRECT_HOUSING_V14_DOCUMENT_AND_ROW_ATTRIBUTES")));

        assertThat(result).hasSize(1);
        verify(reviewer, never()).review(anyList());
    }

    private GeminiNormalizationCorrection correction(
            String target, String field, String attributeName, String attributeValue, Long integer
    ) {
        return new GeminiNormalizationCorrection("notice-1", "housing-1", "supply-1",
                target, field, null, integer, null, null, attributeName, attributeValue,
                "남여공용", "정규화");
    }

    private NoticeSyncCommand command() {
        return command(GeminiPdfHousingExtractor.PROMPT_VERSION);
    }

    private NoticeSyncCommand command(String parserVersion) {
        String raw = "{\"parser\":\"" + parserVersion + "\","
                + "\"evidence\":\"남여공용\",\"unitAdditionalAttributes\":[]}";
        return commandWithRaw(raw);
    }

    private NoticeSyncCommand commandWithRaw(String raw) {
        NoticeSupplyResponse supply = new NoticeSupplyResponse(
                "supply-1", null, "다가구", "청년", null, "101", 1, 1,
                new BigDecimal("20.5"), 1L, "1", 1_000_000L,
                null, null, null, 100_000L, raw,
                1, null, null, null, true, null, null, null, null, List.of()
        );
        NoticeHousingResponse housing = new NoticeHousingResponse(
                "housing-1", "테스트", "서울특별시", "강동구", "서울특별시 강동구 테스트로 1",
                null, "서울특별시 강동구 테스트로 1", "테스트로", null, null, null, null,
                List.of(supply), List.of()
        );
        NoticeResponse notice = new NoticeResponse(
                "LH", "notice-1", "OPEN", "2026년 공고", "LH", null,
                "2026-01-01", null, null, null, null, "https://example.test", null, null,
                List.of(housing), List.of()
        );
        return new NoticeSyncCommand(notice, "{}");
    }
}
