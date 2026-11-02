package com.zipick.notice.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
class NoticeTypeClassifier {

    private static final List<Rule> RULES = List.of(
            new Rule("통합공공임대", "통합공공임대"),
            new Rule("청년신혼부부매입임대리츠", "청년·신혼부부 매입임대리츠"),
            new Rule("신혼·신생아", "매입임대", "신혼·신생아 매입임대"),
            new Rule("신혼ㆍ신생아", "매입임대", "신혼·신생아 매입임대"),
            new Rule("청년", "매입임대", "청년 매입임대"),
            new Rule("고령자", "매입임대", "고령자 매입임대"),
            new Rule("일반", "매입임대", "일반 매입임대"),
            new Rule("기숙사형", "청년", "기숙사형 청년주택"),
            new Rule("든든전세", "든든전세"),
            new Rule("전세임대", "전세임대"),
            new Rule("행복주택", "행복주택"),
            new Rule("국민임대", "국민임대"),
            new Rule("영구임대", "영구임대"),
            new Rule("장기전세", "장기전세"),
            new Rule("매입임대", "매입임대"),
            new Rule("공공임대", "공공임대")
    );

    String classify(String apiValue, String title) {
        if (StringUtils.hasText(apiValue)) {
            return apiValue.trim();
        }
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return RULES.stream()
                .filter(rule -> rule.matches(title))
                .map(Rule::result)
                .findFirst()
                .orElse(null);
    }

    private record Rule(List<String> keywords, String result) {
        private Rule(String keyword, String result) {
            this(List.of(keyword), result);
        }

        private Rule(String first, String second, String result) {
            this(List.of(first, second), result);
        }

        private boolean matches(String value) {
            return keywords.stream().allMatch(value::contains);
        }
    }
}
