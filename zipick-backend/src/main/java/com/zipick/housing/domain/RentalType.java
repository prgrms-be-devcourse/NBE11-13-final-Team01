package com.zipick.housing.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 공공임대 유형. 수집한 공고의 공고 유형(notice.notice_type)을 분류하고, 비어 있으면 공고 제목으로 분류한다.
 * 지원조건 규칙은 유형별로 다르다. 아래 순서대로 먼저 맞는 유형을 고른다(통합공공임대가 공공임대보다 먼저).
 *
 * 매입임대는 대상(청년·신혼·일반)마다 조건이 달라서, 청년 대상만 YOUTH_PURCHASE 로 따로 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum RentalType {
    HAPPY("행복주택"),
    NATIONAL("국민임대"),
    PERMANENT("영구임대"),
    INTEGRATED("통합공공임대"),
    DEUNDEUN("든든전세"),
    JEONSE_LEASE("전세임대"),
    YOUTH_PURCHASE("청년 매입임대"),
    PURCHASE("매입임대"),
    PUBLIC("공공임대(5·10년)");

    private final String label;

    /** 문자열이 이 유형을 가리키는지. SQL 필터(CandidateQueryRepository)도 같은 규칙을 쓴다 */
    public boolean matches(String text) {
        if (text == null) return false;
        return switch (this) {
            case HAPPY -> text.contains("행복주택");
            case NATIONAL -> text.contains("국민임대");
            case PERMANENT -> text.contains("영구임대");
            case INTEGRATED -> text.contains("통합공공임대");
            case DEUNDEUN -> text.contains("든든전세");
            case JEONSE_LEASE -> text.contains("전세임대");
            case YOUTH_PURCHASE -> text.contains("매입") && isYouthOnly(text);
            case PURCHASE -> text.contains("매입") && !isYouthOnly(text);
            case PUBLIC -> (text.contains("공공임대") && !text.contains("통합"))
                    || text.contains("10년임대") || text.contains("5년임대");
        };
    }

    /** 청년만 대상인 공고 ("청년·신혼부부"처럼 섞이면 청년 규칙을 적용하지 않는다) */
    private static boolean isYouthOnly(String text) {
        return text.contains("청년") && !text.contains("신혼");
    }

    /** 공고 유형으로 분류하고, 비어 있으면 제목으로 분류한다. 알 수 없으면 null */
    public static RentalType classify(String noticeType, String title) {
        String text = noticeType != null && !noticeType.isBlank() ? noticeType : title;
        if (text == null || text.isBlank()) return null;
        for (RentalType r : values()) {
            if (r.matches(text)) return r;
        }
        return null;
    }
}
