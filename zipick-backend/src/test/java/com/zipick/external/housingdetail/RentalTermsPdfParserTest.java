package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RentalTermsPdfParserTest {
    @Disabled("소득구간 표의 줄바꿈 처리 및 원문 대조가 완료되지 않았습니다")
    @Test void 실제_소득구간별_임대조건을_주택형별로_보존한다() throws Exception {
        Path path = Path.of("tmp", "ai-investigation", "69197711.pdf");
        assumeTrue(Files.exists(path));
        byte[] bytes = Files.readAllBytes(path);
        var input = new DetailedHousingUnit("u", null, null, null, "전라남도 담양군 담양읍 주소",
                null, null, null, null, null, "41A", "통합공공임대", null, null, null);
        var result = new RentalTermsPdfParser().supplementIncomeBands(bytes, java.util.List.of(input));
        assertThat(result.getFirst().rentDeposit()).isEqualTo(11349000L);
        assertThat(result.getFirst().monthlyRent()).isEqualTo(124700L);
        assertThat(result.getFirst().rentalConditions()).hasSize(6);
        assertThat(result.getFirst().rentalConditions()).anyMatch(c -> c.conditionCode().equals("INCOME_BAND_6")
                && c.rentDeposit().equals(29184000L) && c.monthlyRent().equals(320670L));
    }
    @Test void 명시된_단위와_보증금_계약금_잔금을_검증한다() {
        String page = "임대보증금 (천원) 월임대료(원)\n"
                + "25A 기업체기숙사 21,200 1,000 20,200 128,960(+) 32,200 64,800\n"
                + "36A 기업체기숙사 30,400 1,000 29,400 184,930(+) 45,400 97,430";
        var terms = RentalTermsPdfParser.parsePage(page, 2);
        assertThat(terms).hasSize(2);
        assertThat(terms.getFirst().deposit()).isEqualTo(21_200_000L);
        assertThat(terms.getFirst().monthlyRent()).isEqualTo(128_960L);
        assertThat(RentalTermsPdfParser.parsePage(page.replace("20,200", "20,201"), 2)).hasSize(1);
        assertThat(RentalTermsPdfParser.parsePage(page.replace("천원", "원"), 2)).isEmpty();
    }

    @Test void 실제_GH_공고문에서_두_주택형의_임대조건을_읽는다() throws Exception {
        Path path = Path.of("..", "tmp", "audit-notice-74.pdf");
        assumeTrue(Files.exists(path));
        var rows = new RentalTermsPdfParser().parse(Files.readAllBytes(path));
        assertThat(rows).anyMatch(v -> v.housingType().equals("25A") && v.deposit() == 21_200_000L && v.monthlyRent() == 128_960L);
        assertThat(rows).anyMatch(v -> v.housingType().equals("36A") && v.deposit() == 30_400_000L && v.monthlyRent() == 184_930L);
    }

    @Test void 실제_SH_공고문의_기본금액과_전환금액을_구분한다() throws Exception {
        for (int id : new int[]{60, 70}) {
            Path path = Path.of("..", "tmp", "audit-notice-" + id + ".pdf");
            assumeTrue(Files.exists(path));
            var rows = new RentalTermsPdfParser().parseSingleUnits(Files.readAllBytes(path));
            if (id == 60) assertThat(rows).anyMatch(v -> v.unitNumber().equals("502호") && v.deposit() == 31500000L && v.monthlyRent() == 444300L && v.convertedDeposit() == 79200000L && v.convertedRent() == 177900L);
            else assertThat(rows).anyMatch(v -> v.unitNumber().equals("601호") && v.deposit() == 60375000L && v.monthlyRent() == 540800L && v.convertedDeposit() == 118400000L && v.convertedRent() == 216800L);
            String address = id == 60 ? "서울특별시 구로구 남부순환로105나길 24" : "서울특별시 강동구 천중로44길 16";
            var input = new DetailedHousingUnit("u", null, null, null, address, null, null, null,
                    null, null, null, null, null, null, null);
            var parsed = new RentalTermsPdfParser().supplementSingleUnits(Files.readAllBytes(path), java.util.List.of(input));
            assertThat(parsed.getFirst().monthlyRent()).isEqualTo(id == 60 ? 444300L : 540800L);
            assertThat(parsed.getFirst().rentalConditions()).singleElement()
                    .satisfies(condition -> assertThat(condition.conditionCode()).isEqualTo("MAX_DEPOSIT"));
        }
    }

    @Test void 붙어서_추출된_금액은_쉼표_단위와_주소를_모두_검증한다() throws Exception {
        Path path = Path.of("..", "tmp", "audit-notice-65.pdf");
        assumeTrue(Files.exists(path));
        var input = new DetailedHousingUnit("u", null, null, null, "서울특별시 동대문구 장안벚꽃로 11",
                null, null, null, null, null, null, null, null, null, null);
        var result = new RentalTermsPdfParser().supplementSingleUnits(Files.readAllBytes(path), java.util.List.of(input));
        assertThat(result.getFirst().rentDeposit()).isEqualTo(97000000L);
        assertThat(result.getFirst().monthlyRent()).isEqualTo(457000L);
        assertThat(result.getFirst().unitNumber()).isEqualTo("405호");
        assertThat(result.getFirst().rentalConditions()).isEmpty();
        var wrongAddress = new DetailedHousingUnit("u", null, null, null, "서울특별시 동대문구 장안벚꽃로 12",
                null, null, null, null, null, null, null, null, null, null);
        assertThat(new RentalTermsPdfParser().supplementSingleUnits(Files.readAllBytes(path), java.util.List.of(wrongAddress)).getFirst().monthlyRent()).isNull();
    }

    @Test void 공급위치가_다른_페이지에_있어도_정확히_일치할때만_연결한다() throws Exception {
        Path path = Path.of("..", "tmp", "audit-notice-59.pdf");
        assumeTrue(Files.exists(path));
        var unit = new DetailedHousingUnit("u", null, null, null, "서울특별시 노원구 동일로242길 103",
                null, null, null, null, null, null, null, null, null, null);
        var result = new RentalTermsPdfParser().supplementSingleUnits(Files.readAllBytes(path), java.util.List.of(unit));
        assertThat(result.getFirst().rentDeposit()).isEqualTo(160338400L);
        assertThat(result.getFirst().monthlyRent()).isEqualTo(444000L);
    }
}
