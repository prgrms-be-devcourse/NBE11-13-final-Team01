package com.zipick.external.housingdetail;

import com.zipick.notice.dto.HousingMediaResponse;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HousingDetailEnricherTest {

    @Test
    void mergesDetailedUnitsMediaConditionsAndAttachmentsByAddress() {
        HousingDetailSource source = mock(HousingDetailSource.class);
        NoticeResponse notice = notice(List.of(new NoticeHousingResponse(
                "json-key", null, "서울특별시", "강남구",
                "서울특별시 강남구 테헤란로 10 (역삼동, 기존단지)",
                null, null, null, null, null, "지역난방", "100",
                List.of(), List.of(new HousingMediaResponse(
                        "existing", "FLOOR_PLAN", "old.png", "https://files.lh.or.kr/old.png", "{}"
                ))
        )));
        DetailedHousingUnit first = unit("u1", "101", 3, 2L);
        DetailedHousingUnit second = unit("u2", "102", 4, null);
        NoticeAttachmentResponse attachment = new NoticeAttachmentResponse(
                "a1", "NOTICE", "notice.pdf", "https://files.lh.or.kr/notice.pdf", "{}"
        );
        HousingDetailResult result = new HousingDetailResult(
                List.of(first, second),
                List.of(attachment),
                Map.of(
                        "u1", List.of(new DetailedHousingMedia(
                                "c1", "existing", "FLOOR_PLAN", "duplicate.png",
                                "https://files.lh.or.kr/duplicate.png", "{}"
                        )),
                        "u2", List.of(new DetailedHousingMedia(
                                "c1", "new", "BUILDING_PHOTO", "photo.jpg",
                                "https://files.lh.or.kr/photo.jpg", "{}"
                        ))
                )
        );
        when(source.supports(notice)).thenReturn(true);
        when(source.extract(notice)).thenReturn(result);

        NoticeResponse enriched = new HousingDetailEnricher(List.of(source)).enrich(notice);

        assertThat(enriched.attachments()).containsExactly(attachment);
        assertThat(enriched.houses()).hasSize(1);
        NoticeHousingResponse housing = enriched.houses().getFirst();
        assertThat(housing.sourceHousingKey()).isEqualTo("json-key");
        assertThat(housing.complexName()).isEqualTo("상세단지");
        assertThat(housing.roadName()).isEqualTo("테헤란로");
        assertThat(housing.legalDongName()).isEqualTo("역삼동");
        assertThat(housing.supplies()).hasSize(2);
        assertThat(housing.supplies().getFirst().supplyCount()).isEqualTo(2L);
        assertThat(housing.supplies().get(1).supplyCount()).isEqualTo(1L);
        assertThat(housing.supplies().getFirst().rentalConditions()).hasSize(1);
        assertThat(housing.supplies().getFirst().rawPayload())
                .contains("unitAdditionalAttributes", "베란다");
        assertThat(housing.media()).extracting(HousingMediaResponse::sourceKey)
                .containsExactly("existing", "new");
    }

    @Test
    void addsNewAddressAndSkipsPlaceholderUnit() {
        HousingDetailSource unsupported = mock(HousingDetailSource.class);
        HousingDetailSource source = mock(HousingDetailSource.class);
        NoticeResponse notice = notice(List.of());
        DetailedHousingUnit valid = unit("u1", "101", 2, 1L);
        DetailedHousingUnit placeholder = new DetailedHousingUnit(
                "bad", "예시", "서울특별시", "00구", "서울특별시 00구 0000로 000",
                null, null, null, null, null, null, null, null, null, "{}"
        );
        when(unsupported.supports(notice)).thenReturn(false);
        when(source.supports(notice)).thenReturn(true);
        when(source.extract(notice)).thenReturn(new HousingDetailResult(
                List.of(valid, placeholder), List.of(), Map.of()
        ));

        NoticeResponse enriched = new HousingDetailEnricher(List.of(unsupported, source)).enrich(notice);

        assertThat(enriched.houses()).hasSize(1);
        assertThat(enriched.houses().getFirst().sourceHousingKey()).startsWith("DETAIL:ADDRESS:");
    }

    @Test
    void continuesAfterSourceFailureAndReturnsOriginalWhenNoDetailExists() {
        HousingDetailSource failing = mock(HousingDetailSource.class);
        HousingDetailSource empty = mock(HousingDetailSource.class);
        NoticeHousingResponse original = new NoticeHousingResponse(
                "key", null, null, null, "서울특별시 강남구 테헤란로 10 (역삼동, 테스트빌딩)",
                null, null, null, null, null, null, null, null, null
        );
        NoticeResponse notice = notice(List.of(original));
        when(failing.supports(notice)).thenReturn(true);
        when(failing.extract(notice)).thenThrow(new IllegalStateException("broken"));
        when(empty.supports(notice)).thenReturn(true);
        when(empty.extract(notice)).thenReturn(HousingDetailResult.empty());

        NoticeResponse enriched = new HousingDetailEnricher(List.of(failing, empty)).enrich(notice);

        assertThat(enriched.houses()).hasSize(1);
        assertThat(enriched.houses().getFirst().complexName()).isNull();
        assertThat(enriched.attachments()).isEmpty();
    }

    private NoticeResponse notice(List<NoticeHousingResponse> housings) {
        return new NoticeResponse(
                "LH", "n1", "공고중", "테스트 공고", "LH", null,
                "2026-10-01", null, "2026-10-01", "2099-10-31", null,
                "https://apply.lh.or.kr/n1", null, null, housings, List.of()
        );
    }

    private DetailedHousingUnit unit(String key, String unitNumber, int floor, Long supplyCount) {
        return new DetailedHousingUnit(
                key, "상세단지", "서울특별시", "강남구",
                "서울특별시 강남구 테헤란로 10", "101동", unitNumber, floor,
                new BigDecimal("39.50"), 2, "아파트", "국민임대",
                10_000_000L, 300_000L,
                "{\"unitAdditionalAttributes\":[{\"name\":\"베란다\",\"value\":\"있음\"}]}",
                "서울특별시 강남구 역삼동 1-1",
                "서울특별시 강남구 테헤란로 10", 1, "공급중", "강남역",
                new BigDecimal("0.3"), true, true, 50, false, 0, supplyCount,
                List.of(new DetailedRentalCondition("BASIC", "기본", 10_000_000L, 300_000L, "{}"))
        );
    }
}
