package com.zipick.external.ai;

import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.repository.NoticeRepository;
import com.zipick.notice.service.HousingGeocodingService;
import com.zipick.notice.service.NoticeSyncCommand;
import com.zipick.notice.service.NoticeSyncPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 이미 수집된 공개 공고의 첨부문서만 AI로 다시 읽어 상세 주택 정보를 보완합니다. */
@Service
public class NoticeAiEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(NoticeAiEnrichmentService.class);

    private final NoticeRepository noticeRepository;
    private final HousingDetailEnricher detailEnricher;
    private final NoticeSyncPersistenceService persistenceService;
    private final GeminiNoticeNormalizationService normalizationService;
    private final HousingGeocodingService geocodingService;
    private final GeminiPdfHousingDetailSupport geminiSupport;

    public NoticeAiEnrichmentService(
            NoticeRepository noticeRepository,
            HousingDetailEnricher detailEnricher,
            NoticeSyncPersistenceService persistenceService,
            GeminiNoticeNormalizationService normalizationService,
            HousingGeocodingService geocodingService,
            GeminiPdfHousingDetailSupport geminiSupport
    ) {
        this.noticeRepository = noticeRepository;
        this.detailEnricher = detailEnricher;
        this.persistenceService = persistenceService;
        this.normalizationService = normalizationService;
        this.geocodingService = geocodingService;
        this.geminiSupport = geminiSupport;
    }

    public Result enrichExistingNotices() {
        if (!geminiSupport.enabled()) {
            throw new IllegalStateException("Gemini PDF 파서가 설정되지 않았습니다. GEMINI_API_KEY를 확인해주세요.");
        }

        List<Notice> notices = noticeRepository
                .findByStatusIn(NoticeStatus.PUBLIC_STATUSES, Pageable.unpaged())
                .getContent();
        List<NoticeSyncCommand> normalizationCandidates = new ArrayList<>();
        int processed = 0;
        int detailed = 0;
        int failed = 0;

        for (Notice notice : notices) {
            processed++;
            try {
                NoticeResponse enriched = detailEnricher.enrich(toResponse(notice));
                if (!enriched.houses().isEmpty() || !enriched.attachments().isEmpty()) {
                    persistenceService.synchronizeDetails(notice.getId(), enriched, Instant.now());
                }
                if (!enriched.houses().isEmpty()) {
                    detailed++;
                    normalizationCandidates.add(new NoticeSyncCommand(enriched, "{}"));
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("COLLECTOR_METRIC event=notice_error noticeId={} source={}", notice.getId(), notice.getSource());
                log.warn("AI 상세 주택 보완을 건너뜁니다: noticeId={}, source={}, reason={}",
                        notice.getId(), notice.getSource(), exception.getMessage());
            }
        }

        normalizationService.enqueue(normalizationCandidates);
        // 이전 실행에서 503 등으로 미뤄진 작업도 이번 AI 전용 실행에서 이어서 처리합니다.
        normalizationService.normalizePending();
        geocodingService.enrichMissingCoordinates();
        return new Result(notices.size(), processed, detailed, failed, normalizationCandidates.size());
    }

    private NoticeResponse toResponse(Notice notice) {
        return new NoticeResponse(
                notice.getSource(),
                notice.getExternalNoticeId(),
                notice.getStatus().name(),
                notice.getTitle(),
                notice.getSource(),
                null,
                null,
                null,
                null,
                null,
                null,
                notice.getDetailUrl(),
                notice.getDetailUrl(),
                null,
                List.of(),
                List.of()
        );
    }

    public record Result(
            int candidates,
            int processed,
            int detailed,
            int failed,
            int normalizationCandidates
    ) {
    }
}
