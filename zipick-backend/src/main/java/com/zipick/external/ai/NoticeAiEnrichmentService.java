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

        try (var pdfTiming = GeminiPdfStageTiming.start()) {
        List<Notice> notices = noticeRepository
                .findByStatusIn(NoticeStatus.PUBLIC_STATUSES, Pageable.unpaged())
                .getContent().stream()
                .filter(notice -> !AiReprocessingScope.enabled() || AiReprocessingScope.includes(notice.getExternalNoticeId()))
                .toList();
        List<NoticeSyncCommand> normalizationCandidates = new ArrayList<>();
        int processed = 0;
        int detailed = 0;
        int failed = 0;
        long detailNanos = 0;
        long persistenceNanos = 0;

        for (Notice notice : notices) {
            processed++;
            long started = System.nanoTime();
            boolean detailMeasured = false;
            long noticePersistenceNanos = 0;
            try {
                NoticeResponse enriched = detailEnricher.enrich(toResponse(notice));
                detailNanos += System.nanoTime() - started;
                detailMeasured = true;
                if (!enriched.houses().isEmpty() || !enriched.attachments().isEmpty()) {
                    long persistenceStarted = System.nanoTime();
                    try {
                        persistenceService.synchronizeDetails(notice.getId(), enriched, Instant.now());
                    } finally {
                        noticePersistenceNanos = System.nanoTime() - persistenceStarted;
                        persistenceNanos += noticePersistenceNanos;
                    }
                }
                if (!enriched.houses().isEmpty()) {
                    detailed++;
                    normalizationCandidates.add(new NoticeSyncCommand(enriched, "{}"));
                }
            } catch (RuntimeException exception) {
                if (!detailMeasured) detailNanos += System.nanoTime() - started;
                failed++;
                log.warn("COLLECTOR_METRIC event=notice_error noticeId={} source={}", notice.getId(), notice.getSource());
                log.warn("AI 상세 주택 보완을 건너뜁니다: noticeId={}, source={}, reason={}",
                        notice.getId(), notice.getSource(), exception.getMessage());
            } finally {
                long total = System.nanoTime() - started;
                log.info("AI 공고별 처리 시간: noticeId={}, source={}, 상세처리ms={}, 상세DB저장ms={}, 전체ms={}",
                        notice.getId(), notice.getSource(), (total - noticePersistenceNanos) / 1_000_000,
                        noticePersistenceNanos / 1_000_000, total / 1_000_000);
            }
        }

        long queueStarted = System.nanoTime();
        int eligibleForNormalization = normalizationService.enqueue(normalizationCandidates);
        log.info("AI 정규화 대상 구분: 상세 결과 공고={}, Lite 결과 보유={}, Lite 결과 없음 또는 저장 공고 아님={}",
                normalizationCandidates.size(), eligibleForNormalization, normalizationCandidates.size() - eligibleForNormalization);
        long queueCompleted = System.nanoTime();
        // 이전 실행에서 503 등으로 미뤄진 작업도 이번 AI 전용 실행에서 이어서 처리합니다.
        normalizationService.normalizePending();
        long normalizationCompleted = System.nanoTime();
        if (!AiReprocessingScope.enabled()) geocodingService.enrichMissingCoordinates();
        log.info("AI 수집 구간 시간: 상세처리ms={}, 상세DB저장ms={}, 대기열등록ms={}, 정규화ms={}, 좌표변환ms={}",
                detailNanos / 1_000_000, persistenceNanos / 1_000_000,
                (queueCompleted - queueStarted) / 1_000_000,
                (normalizationCompleted - queueCompleted) / 1_000_000,
                (System.nanoTime() - normalizationCompleted) / 1_000_000);
        return new Result(notices.size(), processed, detailed, failed, normalizationCandidates.size());
        }
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
