package com.zipick.notice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RentalNoticePolicyTest {

    @Test
    void excludesOwnershipTransferAndSaleNotices() {
        assertThat(RentalNoticePolicy.isExcluded("10년 분양전환 공공임대주택 모집공고")).isTrue();
        assertThat(RentalNoticePolicy.isExcluded("분양전환형 든든전세주택")).isTrue();
        assertThat(RentalNoticePolicy.isExcluded("공공분양주택 입주자모집공고")).isTrue();
        assertThat(RentalNoticePolicy.isExcluded("잔여세대 일반매각 공고")).isTrue();
    }

    @Test
    void keepsNonConvertibleRentalNotice() {
        assertThat(RentalNoticePolicy.isExcluded("비분양전환형 든든전세주택 입주자 모집공고")).isFalse();
        assertThat(RentalNoticePolicy.isExcluded("국민임대주택 예비입주자 모집공고")).isFalse();
    }
}
