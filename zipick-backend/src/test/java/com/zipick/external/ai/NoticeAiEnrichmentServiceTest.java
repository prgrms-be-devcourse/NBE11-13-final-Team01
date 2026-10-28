package com.zipick.external.ai;

import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.repository.NoticeRepository;
import com.zipick.notice.service.HousingGeocodingService;
import com.zipick.notice.service.NoticeSyncPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoticeAiEnrichmentServiceTest {

    private final NoticeRepository notices = mock(NoticeRepository.class);
    private final HousingDetailEnricher enricher = mock(HousingDetailEnricher.class);
    private final NoticeSyncPersistenceService persistence = mock(NoticeSyncPersistenceService.class);
    private final GeminiNoticeNormalizationService normalization = mock(GeminiNoticeNormalizationService.class);
    private final HousingGeocodingService geocoding = mock(HousingGeocodingService.class);
    private final GeminiPdfHousingDetailSupport gemini = mock(GeminiPdfHousingDetailSupport.class);
    private NoticeAiEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new NoticeAiEnrichmentService(
                notices, enricher, persistence, normalization, geocoding, gemini);
    }

    @Test
    void enrichesStoredPublicNoticesWithoutRunningBaseCollectors() {
        Notice notice = mock(Notice.class);
        when(notice.getId()).thenReturn(7L);
        when(notice.getSource()).thenReturn("SH");
        when(notice.getExternalNoticeId()).thenReturn("303584");
        when(notice.getTitle()).thenReturn("입주자 모집공고");
        when(notice.getStatus()).thenReturn(NoticeStatus.OPEN);
        when(notice.getDetailUrl()).thenReturn("https://example.com/notice");
        when(gemini.enabled()).thenReturn(true);
        when(notices.findByStatusIn(eq(NoticeStatus.PUBLIC_STATUSES), any()))
                .thenReturn(new PageImpl<>(List.of(notice)));
        NoticeResponse enriched = response(List.of(new NoticeHousingResponse(
                "h1", null, null, null, "서울특별시 강남구 테헤란로 1",
                null, null, null, null, null, null, null, List.of(), List.of()
        )));
        when(enricher.enrich(any())).thenReturn(enriched);

        NoticeAiEnrichmentService.Result result = service.enrichExistingNotices();

        assertThat(result).isEqualTo(new NoticeAiEnrichmentService.Result(1, 1, 1, 0, 1));
        verify(persistence).synchronizeDetails(eq(7L), eq(enriched), any());
        verify(normalization).enqueue(any());
        verify(normalization).normalizePending();
        verify(geocoding).enrichMissingCoordinates();
    }

    @Test
    void rejectsRunWithoutConfiguredGeminiKey() {
        when(gemini.enabled()).thenReturn(false);

        assertThatThrownBy(service::enrichExistingNotices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
        verify(notices, never()).findByStatusIn(any(), any());
    }

    private NoticeResponse response(List<NoticeHousingResponse> houses) {
        return new NoticeResponse(
                "SH", "303584", "OPEN", "입주자 모집공고", "SH",
                null, null, null, null, null, null,
                "https://example.com/notice", "https://example.com/notice", null,
                houses, List.of()
        );
    }
}
