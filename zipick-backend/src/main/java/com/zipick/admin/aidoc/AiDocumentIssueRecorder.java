package com.zipick.admin.aidoc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gemini PDF 보완(GeminiPdfHousingDetailSupport)이 문서 결과를 알려 주는 창구.
 * 기록 실패가 수집을 멈추면 안 되므로 예외는 여기서 삼키고, 수집 트랜잭션과 분리해(REQUIRES_NEW) 바로 커밋한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDocumentIssueRecorder {

    private static final int DETAIL_MAX = 2000;

    private final AiDocumentIssueRepository issues;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String documentKey, String agency, String noticeTitle, String fileName,
                       String reason, Integer expectedUnits, Integer parsedUnits, String detail) {
        if (documentKey == null || documentKey.isBlank()) return;
        try {
            AiDocumentIssue issue = issues.findByDocumentKey(documentKey)
                    .orElseGet(() -> new AiDocumentIssue(documentKey, agency, trim(noticeTitle, 500), trim(fileName, 500)));
            issue.observe(reason, expectedUnits, parsedUnits, trim(detail, DETAIL_MAX));
            issues.save(issue);
        } catch (RuntimeException e) {
            log.warn("AI 문서 오류를 기록하지 못했습니다: documentKey={}, {}", documentKey, e.toString());
        }
    }

    /** 같은 문서가 성공하면 더는 문제가 아니므로 행을 지운다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(String documentKey) {
        if (documentKey == null || documentKey.isBlank()) return;
        try {
            issues.deleteByDocumentKey(documentKey);
        } catch (RuntimeException e) {
            log.warn("AI 문서 오류 해소를 기록하지 못했습니다: documentKey={}, {}", documentKey, e.toString());
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
