package com.zipick.external.sh;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import org.junit.jupiter.api.DisplayName;
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

    // ---- 시도 하드코딩 회귀 ----
    //
    // 이 표 형식은 SH 전용이 아니다. LH·GBDC 상세 수집도 PDF 를 받으면 이 파서를 먼저 돌린다.
    // 예전에는 매칭된 모든 행에 "서울특별시"를 박아 넣어, 서울 밖 주택이 서울로 저장될 수 있었다.
    // 서울특별시는 표준 시도라 RegionNormalizer 가 걸러내지 못하고 지오코딩도 덮지 않아 그대로 굳는다.

    /** 경북 포항시 북구 행. '구'로 끝나는 시군구는 서울 밖에도 많아 이 표 형식에 그대로 매칭된다 */
    private static final String NON_SEOUL_ROW = """
            3 북구 예시주택 51A 북구 죽도동 100 북구 중앙로 1 103호 0103 51.00 2 일반형 신규 30,000,000 300,000 40,000,000 400,000 포항역 1.2 O O 10 X
            """;

    @Test
    @DisplayName("SH 공고는 기존대로 서울특별시가 들어간다")
    void shKeepsSeoul() {
        DetailedHousingUnit unit = parser.parseText("""
                9 강동구 예시주택 34A 강동구 암사동 100-1 강동구 올림픽로 1 (암사동, 예시주택) 101호 0101 34.50 1 일반형 신규 34,650,000 357,100 57,760,000 595,100 암사역 0.59 O X 10 X (02)000-0000
                """, new ShAttachment("GS0401", "1", "2", 100L, "주택목록.pdf", "A")).getFirst();

        assertThat(unit.provinceName()).isEqualTo("서울특별시");
        assertThat(unit.districtName()).isEqualTo("강동구");
        assertThat(unit.lotAddress()).isEqualTo("서울특별시 강동구 암사동 100-1");
        assertThat(unit.roadAddress()).startsWith("서울특별시 강동구 올림픽로 1");
    }

    @Test
    @DisplayName("SH 가 아닌 기관 문서에는 서울특별시를 붙이지 않는다")
    void otherAgencyDocumentGetsNoProvince() {
        DetailedHousingUnit unit = parser
                .parseTextWithoutProvince(NON_SEOUL_ROW, "1", "GBDC_LIST").getFirst();

        assertThat(unit.provinceName()).isNull();                       // 근거가 없으면 비워 둔다
        assertThat(unit.districtName()).isEqualTo("북구");               // 문서에 적힌 값은 유지
        assertThat(unit.lotAddress()).isEqualTo("북구 죽도동 100");       // 없는 지역명을 만들지 않는다
        assertThat(unit.roadAddress()).isEqualTo("북구 중앙로 1");
    }

    @Test
    @DisplayName("기관만 다를 뿐 공급 정보 추출 결과는 그대로다")
    void otherAgencyKeepsEverythingButProvince() {
        DetailedHousingUnit seoul = parser.parseText(
                NON_SEOUL_ROW, new ShAttachment("GS0401", "1", "2", 100L, "x.pdf", "A")).getFirst();
        DetailedHousingUnit other = parser
                .parseTextWithoutProvince(NON_SEOUL_ROW, "1", "GBDC_LIST").getFirst();

        assertThat(other.complexName()).isEqualTo(seoul.complexName());
        assertThat(other.housingType()).isEqualTo(seoul.housingType());
        assertThat(other.unitNumber()).isEqualTo(seoul.unitNumber());
        assertThat(other.exclusiveArea()).isEqualByComparingTo(seoul.exclusiveArea());
        assertThat(other.roomCount()).isEqualTo(seoul.roomCount());
        assertThat(other.supplyType()).isEqualTo(seoul.supplyType());
        assertThat(other.nearbySubwayStation()).isEqualTo(seoul.nearbySubwayStation());
        assertThat(other.totalParkingSpaces()).isEqualTo(seoul.totalParkingSpaces());
        assertThat(other.rentalConditions()).isEqualTo(seoul.rentalConditions());
        // 다른 것은 시도와 시도를 붙인 주소뿐이다
        assertThat(seoul.provinceName()).isEqualTo("서울특별시");
        assertThat(other.provinceName()).isNull();
    }

    @Test
    @DisplayName("주소가 이미 서울로 시작하면 덧붙이지 않는다")
    void doesNotDuplicateExistingProvince() {
        // PDF 에서 시도와 시군구가 붙어 나오는 줄. 표 구조상 "서울특별시 강남구" 처럼
        // 띄어 쓴 형태는 정규식(\S+구)에 걸리지 않으므로 붙여 쓴 형태로 확인한다.
        DetailedHousingUnit unit = parser.parseText("""
                4 서울강남구 예시주택 44A 서울강남구 역삼동 1 서울강남구 테헤란로 1 101호 0101 44.00 2 일반형 신규 30,000,000 300,000 40,000,000 400,000 강남역 0.2 O O 10 X
                """, new ShAttachment("GS0401", "1", "2", 100L, "주택목록.pdf", "A")).getFirst();

        assertThat(unit.provinceName()).isEqualTo("서울특별시");
        assertThat(unit.lotAddress()).isEqualTo("서울강남구 역삼동 1");
        assertThat(unit.roadAddress()).isEqualTo("서울강남구 테헤란로 1");
    }
}
