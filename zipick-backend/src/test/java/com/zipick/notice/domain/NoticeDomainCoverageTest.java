package com.zipick.notice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoticeDomainCoverageTest {

    @Test
    void agencyAttachmentMediaAndParseHistoryExposeSynchronizedValues() {
        Agency agency = new Agency("LH", " LH ", "https://old");
        agency.update(" 한국토지주택공사 ", " https://new ");
        assertThat(agency.getCode()).isEqualTo("LH");
        assertThat(agency.getName()).isEqualTo("한국토지주택공사");
        assertThat(agency.getSourceUrl()).isEqualTo("https://new");
        agency.update(" ", null);
        assertThat(agency.getName()).isEqualTo("한국토지주택공사");

        Notice notice = new Notice("LH", "n", "공고", NoticeStatus.OPEN);
        NoticeAttachment attachment = new NoticeAttachment(notice, "a", "NOTICE", "https://old");
        attachment.synchronize("FLOOR_PLAN", "plan.png", "https://new", "{}");
        assertThat(attachment.getNotice()).isSameAs(notice);
        assertThat(attachment.getAttachmentType()).isEqualTo("FLOOR_PLAN");
        assertThat(attachment.getFileName()).isEqualTo("plan.png");

        Housing housing = new Housing(notice, "h", "단지", "서울 강남구 1");
        HousingMedia media = new HousingMedia(housing, "m", "PHOTO", "https://old");
        media.synchronize("BUILDING_PHOTO", "photo.jpg", "https://new", "{}");
        assertThat(media.getHousing()).isSameAs(housing);
        assertThat(media.getMediaType()).isEqualTo("BUILDING_PHOTO");

        DocumentParseHistory history = new DocumentParseHistory("doc", "v1");
        assertThat(history.isSuccess()).isFalse();
        history.markSuccess(100L, 3);
        assertThat(history.isSuccess()).isTrue();
    }

    @Test
    void housingValidatesAddressSynchronizesAndChangesActiveState() {
        Notice notice = new Notice("LH", "n", "공고", NoticeStatus.OPEN);
        assertThatThrownBy(() -> new Housing(notice, "h", "단지", " "))
                .isInstanceOf(IllegalArgumentException.class);
        Housing housing = new Housing(notice, "h", null, " 서울 강남구 1 ");
        Instant seen = Instant.parse("2026-10-20T00:00:00Z");
        housing.synchronize(
                "pnu", " 단지 ", " 서울 ", " 강남구 ", "서울 강남구 2",
                "normalized", "테헤란로", "역삼동 1", "테헤란로 1", "역삼동",
                "지역난방", 100, "{}", seen
        );
        housing.updateCoordinates(new BigDecimal("37.5"), new BigDecimal("127.0"));

        assertThat(housing.getPnu()).isEqualTo("pnu");
        assertThat(housing.getAddress()).isEqualTo("서울 강남구 2");
        assertThat(housing.getLotAddress()).isEqualTo("역삼동 1");
        assertThat(housing.getRoadAddress()).isEqualTo("테헤란로 1");
        assertThat(housing.getHeatingMethod()).isEqualTo("지역난방");
        assertThat(housing.getTotalHouseholdCount()).isEqualTo(100);
        assertThat(housing.markMissing(LocalDate.of(2026, 10, 20), 2)).isFalse();
        assertThat(housing.markMissing(LocalDate.of(2026, 10, 20), 2)).isFalse();
        assertThat(housing.markMissing(LocalDate.of(2026, 10, 21), 2)).isTrue();
        assertThat(housing.isActive()).isFalse();
        housing.markSeen(seen.plusSeconds(1));
        assertThat(housing.isActive()).isTrue();
    }

    @Test
    void supplyAndRentalConditionSynchronizeAndTrackMissingDays() {
        Housing housing = new Housing(
                new Notice("LH", "n", "공고", NoticeStatus.OPEN), "h", "단지", "서울 강남구 1"
        );
        HousingSupply supply = new HousingSupply(housing, "s", 1L);
        Instant seen = Instant.parse("2026-10-20T00:00:00Z");
        supply.synchronize(
                2L, " 아파트 ", " 국민임대 ", "101동", "1001호", 10, 3, 7,
                new BigDecimal("39.5"), 2, 2, 10_000_000L, 1_000_000L,
                2_000_000L, 7_000_000L, 300_000L, "공급중", "강남역",
                new BigDecimal("0.4"), true, false, 50, true, 10, "{}", seen
        );
        assertThat(supply.getExternalHouseId()).isEqualTo(2L);
        assertThat(supply.getHousingType()).isEqualTo("아파트");
        assertThat(supply.getBuildingDong()).isEqualTo("101동");
        assertThat(supply.getExclusiveArea()).isEqualByComparingTo("39.5");
        assertThat(supply.getElevatorAvailable()).isTrue();
        assertThat(supply.markMissing(LocalDate.of(2026, 10, 20), 2)).isFalse();
        assertThat(supply.markMissing(LocalDate.of(2026, 10, 21), 2)).isTrue();
        assertThat(supply.isActive()).isFalse();
        supply.markSeen(seen.plusSeconds(1));
        assertThat(supply.isActive()).isTrue();

        HousingSupplyCondition condition = new HousingSupplyCondition(supply, "BASIC", "기존");
        condition.synchronize(" 기본 ", 10_000_000L, 300_000L, "{}");
        condition.synchronize(" ", null, null, null);
        assertThat(condition.getHousingSupply()).isSameAs(supply);
        assertThat(condition.getConditionCode()).isEqualTo("BASIC");
        assertThat(condition.getConditionName()).isEqualTo("기본");
        assertThat(condition.getRentDeposit()).isEqualTo(10_000_000L);
    }
}
