package com.zipick.external.ai;

import com.zipick.admin.aidoc.AiDocumentIssueRecorder;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 기존 PDF 파싱 결과를 Gemini의 전체 문서 해석 결과로 보완한다. */
@Component
public class GeminiPdfHousingDetailSupport {

    private static final Logger log = LoggerFactory.getLogger(GeminiPdfHousingDetailSupport.class);

    private final GeminiPdfParserProperties properties;
    private final GeminiPdfHousingExtractor extractor;
    /** 운영자 화면의 "AI 미완료 문서" 목록. 없으면(단위 테스트) 기록하지 않는다 */
    private final AiDocumentIssueRecorder issueRecorder;

    public GeminiPdfHousingDetailSupport(
            GeminiPdfParserProperties properties,
            GeminiPdfHousingExtractor extractor
    ) {
        this(properties, extractor, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiPdfHousingDetailSupport(
            GeminiPdfParserProperties properties,
            GeminiPdfHousingExtractor extractor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AiDocumentIssueRecorder issueRecorder
    ) {
        this.properties = properties;
        this.extractor = extractor;
        this.issueRecorder = issueRecorder;
    }

    public boolean enabled() {
        return properties.isConfigured();
    }

    public String parserVersion() {
        return GeminiPdfHousingExtractor.PROMPT_VERSION;
    }

    /** 완전성 검증을 통과한 AI 결과만 사용하고, 실패하거나 불완전하면 기존 파서 결과를 유지한다. */
    public List<DetailedHousingUnit> supplement(
            byte[] pdfBytes,
            String documentKey,
            String agency,
            String noticeTitle,
            String fileName,
            List<DetailedHousingUnit> fallback
    ) {
        return supplementWithStatus(pdfBytes, documentKey, agency, noticeTitle, fileName, fallback).units();
    }

    public SupplementResult supplementWithStatus(
            byte[] pdfBytes,
            String documentKey,
            String agency,
            String noticeTitle,
            String fileName,
            List<DetailedHousingUnit> fallback
    ) {
        List<DetailedHousingUnit> safeFallback = fallback == null ? List.of() : List.copyOf(fallback);
        if (!enabled()) return new SupplementResult(safeFallback, false, true);
        try {
            GeminiPdfExtractionResult result = extractor.extract(List.of(new GeminiPdfDocument(
                    documentKey, agency, noticeTitle, fileName, pdfBytes,
                    safeFallback.isEmpty() ? null : safeFallback.size()
            )));
            if (!result.complete()) {
                log.warn("COLLECTOR_METRIC event=ai_document_error stage=extraction reason=incomplete documentKey={}", documentKey);
                log.warn("Gemini PDF 결과 미사용: documentKey={}, units={}/{}, warnings={}",
                        documentKey, result.units().size(), result.expectedUnitCount(), result.warnings());
                recordIssue(documentKey, agency, noticeTitle, fileName, "incomplete",
                        result.expectedUnitCount(), result.units().size(), String.valueOf(result.warnings()));
                return new SupplementResult(safeFallback, false, false);
            }
            if (result.units().isEmpty() && !safeFallback.isEmpty()) {
                log.warn("COLLECTOR_METRIC event=ai_document_error stage=extraction reason=empty documentKey={}", documentKey);
                log.warn("Gemini PDF 빈 결과 미사용: documentKey={}, fallbackUnits={}",
                        documentKey, safeFallback.size());
                recordIssue(documentKey, agency, noticeTitle, fileName, "empty",
                        result.expectedUnitCount(), 0, "fallbackUnits=" + safeFallback.size());
                return new SupplementResult(safeFallback, false, false);
            }
            log.info("Gemini PDF 상세정보 적용: documentKey={}, units={}, calls={}",
                    documentKey, result.units().size(), result.apiCallCount());
            if (issueRecorder != null) issueRecorder.succeeded(documentKey);
            return new SupplementResult(result.units(), true, true);
        } catch (RuntimeException exception) {
            log.warn("COLLECTOR_METRIC event=ai_document_error stage=extraction reason=exception documentKey={}", documentKey);
            log.warn("Gemini PDF 보완 실패로 기존 파서 유지: documentKey={}, reason={}",
                    documentKey, exception.getMessage());
            recordIssue(documentKey, agency, noticeTitle, fileName, "exception", null, null, exception.toString());
            return new SupplementResult(safeFallback, false, false);
        }
    }

    private void recordIssue(String documentKey, String agency, String noticeTitle, String fileName,
                             String reason, Integer expected, Integer parsed, String detail) {
        if (issueRecorder != null) issueRecorder.failed(documentKey, agency, noticeTitle, fileName, reason, expected, parsed, detail);
    }

    public record SupplementResult(
            List<DetailedHousingUnit> units,
            boolean aiApplied,
            boolean aiComplete
    ) {
        public SupplementResult {
            units = units == null ? List.of() : List.copyOf(units);
        }
    }
}
