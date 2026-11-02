package com.zipick.external.ai;

import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.NoticeRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiNormalizationPatchPersistenceServiceTest {
    private final NoticeRepository notices = mock(NoticeRepository.class);
    private final HousingRepository housings = mock(HousingRepository.class);
    private final AiNormalizationPatchPersistenceService service = new AiNormalizationPatchPersistenceService(
            notices, housings, mock(HousingSupplyRepository.class));

    @Test
    void 병합된_주택은_같은_공고의_유일하게_일치하는_주소로_찾는다() {
        Notice notice = notice();
        Housing housing = mock(Housing.class);
        when(housing.getAddress()).thenReturn("서울특별시 강남구 테헤란로 1");
        when(housings.findAllByNoticeId(1L)).thenReturn(List.of(housing));
        service.apply(Map.of("LH:2", job()), List.of(correction()));
        verify(housing).applyAiNormalization("단지명", null, null, null, null, null);
    }

    @Test
    void 주소가_중복되어_대상을_확정할수_없으면_반영하지_않는다() {
        notice();
        Housing one = mock(Housing.class);
        Housing two = mock(Housing.class);
        when(one.getAddress()).thenReturn("서울특별시 강남구 테헤란로 1");
        when(two.getAddress()).thenReturn("서울특별시 강남구 테헤란로 1");
        when(housings.findAllByNoticeId(1L)).thenReturn(List.of(one, two));
        assertThatThrownBy(() -> service.apply(Map.of("LH:2", job()), List.of(correction())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("정규화 대상 주택");
        verify(one, never()).applyAiNormalization(any(), any(), any(), any(), any(), any());
        verify(two, never()).applyAiNormalization(any(), any(), any(), any(), any(), any());
    }

    private Notice notice() {
        Notice notice = mock(Notice.class);
        when(notice.getId()).thenReturn(1L);
        when(notices.findBySourceAndExternalNoticeId("LH", "2")).thenReturn(Optional.of(notice));
        return notice;
    }
    private AiNormalizationJob job() {
        AiNormalizationJob job = mock(AiNormalizationJob.class);
        when(job.getSource()).thenReturn("LH");
        when(job.getDocumentId()).thenReturn("LH:2");
        when(job.getInputPayload()).thenReturn("{\"databaseCandidate\":{\"rows\":[{\"sourceHousingKey\":\"new\",\"fullAddress\":\"서울특별시 강남구 테헤란로 1\"}]}}");
        return job;
    }
    private GeminiNormalizationCorrection correction() {
        return new GeminiNormalizationCorrection("LH:2", "new", null, "HOUSING", "complexName",
                "단지명", null, null, null, null, null, "근거", "정규화");
    }
}
