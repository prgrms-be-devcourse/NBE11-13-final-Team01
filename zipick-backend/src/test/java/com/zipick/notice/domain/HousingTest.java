package com.zipick.notice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class HousingTest {

    @Test
    void geocodingPreservesCollectedValuesAndFillsOnlyMissingFields() {
        Notice notice = new Notice("MYHOME", "notice-1", "공고", NoticeStatus.OPEN);
        Housing housing = new Housing(notice, "housing-1", "수집된 주택명", "서울특별시 강남구 테헤란로 1");
        housing.synchronize(
                null, null, "서울특별시", "강남구", null,
                "서울특별시 강남구 테헤란로 1", null, null,
                null, null, null, null
        );

        housing.enrichAddress(
                "서울 강남구 테헤란로 1",
                "카카오 시도",
                "카카오 시군구",
                "역삼동",
                "테헤란로",
                "카카오 건물명",
                new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000")
        );

        assertThat(housing.getAddress()).isEqualTo("서울특별시 강남구 테헤란로 1");
        assertThat(housing.getNormalizedAddress()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(housing.getProvinceName()).isEqualTo("서울특별시");
        assertThat(housing.getDistrictName()).isEqualTo("강남구");
        assertThat(housing.getLegalDongName()).isEqualTo("역삼동");
        assertThat(housing.getRoadName()).isEqualTo("테헤란로");
        assertThat(housing.getName()).isEqualTo("수집된 주택명");
        assertThat(housing.hasCoordinates()).isTrue();
    }
}
