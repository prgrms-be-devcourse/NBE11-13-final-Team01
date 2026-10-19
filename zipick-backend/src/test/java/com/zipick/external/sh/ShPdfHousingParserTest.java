package com.zipick.external.sh;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShPdfHousingParserTest {

    private final ShPdfHousingParser parser = new ShPdfHousingParser();

    @Test
    void parsesDetailedHousingListWithoutContactNumber() {
        String text = """
                31 강북구 다드림13차 57C 강북구 미아동 1378 강북구 솔매로17가길 7 (미아동, 다드림 13차) 106호 0106 58.63 2 전세형 재공급 36,110,000 3 72,100 60,180,000 620,200 삼양역 0.3 O O 36 X (02)944-8000~1
                """;

        List<DetailedHousingUnit> result = parser.parseText(
                text, new ShAttachment("GS0401", "310650", "4", 911179L, "주택목록(상세).pdf", "A")
        );

        assertThat(result).hasSize(1);
        DetailedHousingUnit unit = result.getFirst();
        assertThat(unit.complexName()).isEqualTo("다드림13차");
        assertThat(unit.housingType()).isEqualTo("57C");
        assertThat(unit.lotAddress()).isEqualTo("서울특별시 강북구 미아동 1378");
        assertThat(unit.roadAddress()).isEqualTo("서울특별시 강북구 솔매로17가길 7 (미아동, 다드림 13차)");
        assertThat(unit.unitNumber()).isEqualTo("0106");
        assertThat(unit.exclusiveArea()).isEqualByComparingTo("58.63");
        assertThat(unit.roomCount()).isEqualTo(2);
        assertThat(unit.nearbySubwayStation()).isEqualTo("삼양역");
        assertThat(unit.subwayWalkDistanceKm()).isEqualByComparingTo(new BigDecimal("0.3"));
        assertThat(unit.elevatorAvailable()).isTrue();
        assertThat(unit.communityFacilityAvailable()).isTrue();
        assertThat(unit.totalParkingSpaces()).isEqualTo(36);
        assertThat(unit.mechanicalParkingAvailable()).isFalse();
        assertThat(unit.rentalConditions()).extracting("rentDeposit", "monthlyRent")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(36_110_000L, 372_100L),
                        org.assertj.core.groups.Tuple.tuple(60_180_000L, 620_200L)
                );
        assertThat(unit.rawPayload()).doesNotContain("944-8000");
    }

    @Test
    void parsesMechanicalParkingCounts() {
        String text = """
                9 강동구 예시주택 34A 강동구 암사동 100-1 강동구 올림픽로 1 (암사동, 예시주택) 101호 0101 34.50 1 일반형 신규 34,650,000 3 57,100 57,760,000 595,100 암사역 0.59 O X 10(10) O 4 9,000 (02)000-0000
                """;

        DetailedHousingUnit unit = parser.parseText(
                text, new ShAttachment("GS0401", "1", "2", 100L, "주택목록.pdf", "A")
        ).getFirst();

        assertThat(unit.totalParkingSpaces()).isEqualTo(10);
        assertThat(unit.mechanicalParkingSpaces()).isEqualTo(10);
        assertThat(unit.mechanicalParkingAvailable()).isTrue();
    }

    @Test
    void parsesSubwayStationContainingSpaces() {
        String text = """
                205 도봉구 다드림11차 B동 77E 도봉구 쌍문동 737 도봉구 우이천로38라길 46-3 (쌍문동, 다드림11차) B동 202호 0202 77.52 3 전세형 재공급 38,520,000 397,000 64,210,000 661,600 4.19 민주묘지역 1.5 O X 16 X (02)6098-1200
                """;

        DetailedHousingUnit unit = parser.parseText(
                text, new ShAttachment("GS0401", "1", "2", 100L, "주택목록.pdf", "A")
        ).getFirst();

        assertThat(unit.nearbySubwayStation()).isEqualTo("4.19 민주묘지역");
    }
}
