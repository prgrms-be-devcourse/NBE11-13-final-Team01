package com.zipick.applicationguide.document;

import com.zipick.applicationguide.dto.GuideSourceRef;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini 에 실제로 보낼 공고문 텍스트와, 그 안의 SOURCE ID 가 가리키는 곳.
 *
 * @param text          "[SOURCE_ID: S1] FILE: ... PAGE: 12" 머리말이 붙은 본문
 * @param sources       S1 -> 모집공고.pdf 12쪽
 * @param categories    실제로 찾아낸 영역. 로그·검증용
 * @param originalChars 줄이기 전 글자 수
 * @param bodyChars     고른 덩어리의 본문 글자 수 (머리말 제외)
 */
public record ReducedDocument(
        String text,
        Map<String, GuideSourceRef> sources,
        Set<SectionCategory> categories,
        int originalChars,
        int bodyChars,
        List<Integer> selectedPages
) {
    public ReducedDocument {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        selectedPages = selectedPages == null ? List.of() : List.copyOf(selectedPages);
    }

    /** 실제로 전송되는 길이 (본문 + 출처 머리말) */
    public int sentChars() {
        return text == null ? 0 : text.length();
    }

    /**
     * 원문에서 몇 %를 덜어냈는지. 본문 기준이다.
     *
     * 전송 길이로 계산하면 출처 머리말([SOURCE_ID: S1] FILE: ... PAGE: n) 때문에
     * 짧은 공고문에서는 음수가 나와 "줄였는데 늘었다" 처럼 읽힌다. 머리말은 우리가 덧붙이는 것이고
     * 선별 효과와는 별개라 본문만으로 센다.
     */
    public int reductionPercent() {
        if (originalChars <= 0) return 0;
        return (int) Math.round(100.0 - (bodyChars * 100.0 / originalChars));
    }

    /** 출처 머리말이 차지하는 글자 수 */
    public int sourceHeaderChars() {
        return Math.max(0, sentChars() - bodyChars);
    }
}
