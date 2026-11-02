package com.zipick.notice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeTypeClassifierTest {

    private final NoticeTypeClassifier classifier = new NoticeTypeClassifier();

    @Test
    void API_공급유형이_있으면_제목보다_우선한다() {
        assertThat(classifier.classify("API 공공임대", "청년 매입임대 모집공고"))
                .isEqualTo("API 공공임대");
    }

    @Test
    void API_공급유형이_없을_때만_제목에서_분류한다() {
        assertThat(classifier.classify(null, "[강원지역본부] 26년 2차 일반 매입임대주택 모집공고"))
                .isEqualTo("일반 매입임대");
        assertThat(classifier.classify(null, "2026년 청년 매입임대주택 모집공고"))
                .isEqualTo("청년 매입임대");
    }
}
