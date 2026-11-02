package com.zipick.applicationguide.service;

import com.zipick.applicationguide.dto.GuideResult;
import com.zipick.applicationguide.dto.GuideSourceRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Gemini 가 돌려준 결과를 사용자에게 보여 주기 전에 다듬는다.
 *
 * <h3>왜 필요한가</h3>
 * 스키마로 형태는 강제할 수 있지만 <b>내용까지 보장되지는 않는다.</b> 특히 출처가 그렇다.
 * 모델이 S99 처럼 우리가 준 적 없는 ID 를 만들어 내면, 화면에는 "모집공고.pdf 99쪽" 같은
 * 존재하지 않는 근거가 그대로 뜬다. 사용자가 원문에서 확인하려다 찾지 못하면
 * 가이드 전체의 신뢰가 무너지므로, 준 적 없는 ID 는 서버에서 조용히 지운다.
 *
 * 항목 자체를 버리지는 않는다. 출처가 없어도 내용은 쓸모가 있고,
 * 무엇보다 제출서류가 통째로 사라지는 쪽이 사용자에게 더 위험하다.
 */
@Slf4j
final class GuideResultSanitizer {

    private GuideResultSanitizer() {
    }

    /**
     * 실제로 제공한 SOURCE ID 만 남기고, 빈 항목을 정리한다.
     *
     * @param known 이번 요청에서 제공한 SOURCE ID
     */
    static GuideResult sanitize(GuideResult result, Map<String, GuideSourceRef> known) {
        Set<String> allowed = known.keySet();
        int[] dropped = {0};

        List<GuideResult.PersonalCheck> checks = result.personalChecks().stream()
                .filter(check -> hasText(check.title()))
                .map(check -> new GuideResult.PersonalCheck(check.title(), check.status(), check.detail(),
                        keepKnown(check.sourceIds(), allowed, dropped)))
                .toList();

        List<GuideResult.DocumentItem> documents = result.documents().stream()
                .filter(document -> hasText(document.name()))
                .map(document -> new GuideResult.DocumentItem(document.name(), document.category(),
                        document.reason(), blankToNull(document.requirements()),
                        keepKnown(document.sourceIds(), allowed, dropped)))
                .toList();

        List<GuideResult.Caution> cautions = result.cautions().stream()
                .filter(caution -> hasText(caution.text()))
                .map(caution -> new GuideResult.Caution(caution.kind(), caution.text(),
                        keepKnown(caution.sourceIds(), allowed, dropped)))
                .toList();

        if (dropped[0] > 0) {
            log.warn("AI 가 제공하지 않은 출처 {}건을 응답에서 제거했습니다. 제공한 출처={}", dropped[0], allowed);
        }
        return new GuideResult(result.summary(), checks, documents, cautions);
    }

    private static List<String> keepKnown(List<String> sourceIds, Set<String> allowed, int[] dropped) {
        List<String> kept = sourceIds.stream().filter(allowed::contains).distinct().toList();
        dropped[0] += sourceIds.size() - kept.size();
        return kept;
    }

    /** 비어 있으면 화면에서 줄 하나를 덜 그리도록 null 로 둔다 */
    private static String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
