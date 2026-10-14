package com.zipick.notice.service;

import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MyHomeNoticeAggregatorTest {

    private final MyHomeNoticeAggregator aggregator = new MyHomeNoticeAggregator();

    @Test
    void groupsRowsWithSameNoticeIdAndKeepsDifferentHouses() {
        List<NoticeResponse> result = aggregator.aggregate(List.of(
                notice("20901", 1L, "111", "서울시 첫 번째 주소", "단지 A"),
                notice("20901", 2L, "222", "서울시 두 번째 주소", "단지 B"),
                notice("20902", 1L, "333", "부산시 주소", "단지 C")
        ));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().source()).isEqualTo("MYHOME");
        assertThat(result.getFirst().noticeId()).isEqualTo("20901");
        assertThat(result.getFirst().houses()).hasSize(2);
    }

    @Test
    void fillsMissingAddressOnlyWhenSameHouseHasOneKnownLocation() {
        List<NoticeResponse> result = aggregator.aggregate(List.of(
                notice("20901", 1L, null, null, "단지 A"),
                notice("20901", 1L, "111", "서울시 주소", "단지 A")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().houses()).hasSize(1);
        assertThat(result.getFirst().houses().getFirst().fullAddress()).isEqualTo("서울시 주소");
        assertThat(result.getFirst().houses().getFirst().pnu()).isEqualTo("111");
        assertThat(result.getFirst().houses().getFirst().supplies()).hasSize(1);
    }

    @Test
    void doesNotMergeConflictingLocationsEvenWhenHouseSnIsSame() {
        List<NoticeResponse> result = aggregator.aggregate(List.of(
                notice("20901", 1L, "111", "서울시 첫 번째 주소", "단지 A"),
                notice("20901", 1L, "222", "서울시 두 번째 주소", "단지 B")
        ));

        assertThat(result.getFirst().houses()).hasSize(2);
        assertThat(result.getFirst().houses())
                .extracting(house -> house.fullAddress())
                .containsExactly("서울시 첫 번째 주소", "서울시 두 번째 주소");
    }

    @Test
    void doesNotAttachAnUnlocatedDifferentHouseToTheOnlyKnownAddress() {
        List<NoticeResponse> result = aggregator.aggregate(List.of(
                notice("20901", 1L, "111", "서울시 주소", "단지 A"),
                notice("20901", 2L, null, null, "단지 B")
        ));

        assertThat(result.getFirst().houses()).hasSize(2);
        assertThat(result.getFirst().houses())
                .extracting(house -> house.fullAddress())
                .containsExactly("서울시 주소", null);
    }

    @Test
    void groupsDifferentSupplyConditionsUnderOneAddress() {
        List<NoticeResponse> result = aggregator.aggregate(List.of(
                notice("20901", 1L, "111", "서울시 같은 주소", "단지 A"),
                notice("20901", 2L, "111", "서울시 같은 주소", "단지 A")
        ));

        assertThat(result.getFirst().houses()).hasSize(1);
        assertThat(result.getFirst().houses().getFirst().supplies()).hasSize(2);
        assertThat(result.getFirst().houses().getFirst().supplies())
                .extracting(supply -> supply.externalHouseId())
                .containsExactly(1L, 2L);
    }

    private MyHomeNotice notice(
            String noticeId,
            Long houseSn,
            String pnu,
            String address,
            String complexName
    ) {
        return new MyHomeNotice(
                "10",
                noticeId,
                houseSn,
                "접수중",
                "테스트 공고",
                "LH",
                "행복주택",
                "공공임대",
                null,
                "2026-10-01",
                "2026-11-01",
                null,
                "https://example.com/notices/" + noticeId,
                null,
                null,
                complexName,
                "서울특별시",
                "강남구",
                address,
                null,
                null,
                pnu,
                "지역난방",
                "100",
                10L,
                10_000_000L,
                1_000_000L,
                2_000_000L,
                7_000_000L,
                150_000L,
                "2026-10-10",
                "2026-10-15"
        );
    }
}
