package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LhSpreadsheetHousingParserTest {

    private final LhSpreadsheetHousingParser parser = new LhSpreadsheetHousingParser();

    @Test
    void 실제_지역기관_공급목록의_금액과_조건을_읽는다() throws Exception {
        Path youth = Path.of("..", "tmp", "gbdc-56.xlsx");
        Path family = Path.of("..", "tmp", "gbdc-55.xlsx");
        Path thousand = Path.of("..", "tmp", "gbdc-58.xlsx");
        assumeTrue(Files.isRegularFile(youth) && Files.isRegularFile(family) && Files.isRegularFile(thousand));
        var youngUnits = parser.parse(Files.readAllBytes(youth), new LhAttachment("test", "목록.xlsx"));
        assertThat(youngUnits.getFirst().monthlyRent()).isEqualTo(168600L);
        assertThat(youngUnits.getFirst().rentalConditions()).extracting(DetailedRentalCondition::monthlyRent)
                .contains(168600L, 205500L);
        var familyUnits = parser.parse(Files.readAllBytes(family), new LhAttachment("test", "목록.xlsx"));
        assertThat(familyUnits.getFirst().rentalConditions()).extracting(DetailedRentalCondition::monthlyRent)
                .contains(93900L, 133900L);
        var thousandUnits = parser.parse(Files.readAllBytes(thousand), new LhAttachment("test", "목록.xlsx"));
        assertThat(thousandUnits.getFirst().monthlyRent()).isEqualTo(30000L);
    }

    @Test
    void 순위별_동일한_열_이름을_구분해_두번째_조건도_보존한다() throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var first = sheet.createRow(0);
            first.createCell(0).setCellValue("주소");
            first.createCell(1).setCellValue("1순위");
            first.createCell(4).setCellValue("2, 3순위");
            var second = sheet.createRow(1);
            second.createCell(1).setCellValue("임대보증금(원)");
            second.createCell(2).setCellValue("당초 월임대료(원)");
            second.createCell(3).setCellValue("반값주택 임대료(원)");
            second.createCell(4).setCellValue("임대보증금(원)");
            second.createCell(5).setCellValue("당초 월임대료(원)");
            second.createCell(6).setCellValue("반값주택 임대료(원)");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("경상북도 영주시 대동로 1");
            row.createCell(1).setCellValue(1000000);
            row.createCell(2).setCellValue(337300);
            row.createCell(3).setCellValue(168600);
            row.createCell(4).setCellValue(2000000);
            row.createCell(5).setCellValue(411000);
            row.createCell(6).setCellValue(205500);
            workbook.write(output);
            var unit = parser.parse(output.toByteArray(), new LhAttachment("test", "목록.xlsx")).getFirst();
            assertThat(unit.rentDeposit()).isEqualTo(1000000L);
            assertThat(unit.monthlyRent()).isEqualTo(168600L);
            assertThat(unit.rentalConditions()).hasSize(2);
            assertThat(unit.rentalConditions().get(0).rentDeposit()).isEqualTo(1000000L);
            assertThat(unit.rentalConditions().get(0).monthlyRent()).isEqualTo(168600L);
            assertThat(unit.rentalConditions().get(1)).satisfies(condition -> {
                assertThat(condition.rentDeposit()).isEqualTo(2000000L);
                assertThat(condition.monthlyRent()).isEqualTo(205500L);
            });
        }
    }

    @Test
    void 당초_월세가_아닌_명시된_실제_지원_임대료를_사용한다() throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("주소");
            header.createCell(1).setCellValue("당초 월 임대료");
            header.createCell(2).setCellValue("반값주택 임대료");
            header.createCell(3).setCellValue("천원주택 임대료");
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("경상북도 영주시 대동로 1");
            first.createCell(1).setCellValue(337300);
            first.createCell(2).setCellValue(168600);
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue("경상북도 칠곡군 왜관읍 달오로 1");
            second.createCell(1).setCellValue(192300);
            second.createCell(3).setCellValue(30000);
            var ambiguous = sheet.createRow(3);
            ambiguous.createCell(0).setCellValue("경상북도 영주시 대동로 2");
            ambiguous.createCell(2).setCellValue(50000);
            ambiguous.createCell(3).setCellValue(30000);
            workbook.write(output);
            var units = parser.parse(output.toByteArray(), new LhAttachment("test", "목록.xlsx"));
            assertThat(units).hasSize(3);
            assertThat(units.get(0).monthlyRent()).isEqualTo(168600L);
            assertThat(units.get(1).monthlyRent()).isEqualTo(30000L);
            assertThat(units.get(2).monthlyRent()).isNull();
        }
    }

    @Test
    void 실제_다단_헤더_공급목록의_32행_전용면적을_읽는다() throws Exception {
        Path path = Path.of("tmp", "ai-investigation", "69110456.xlsx");
        assumeTrue(Files.isRegularFile(path));
        var units = parser.parse(Files.readAllBytes(path), new LhAttachment("69110456", "공급목록.xlsx"));
        assertThat(units).hasSize(32).allMatch(unit -> unit.exclusiveArea() != null);
        assertThat(units).anyMatch(unit -> "403".equals(unit.unitNumber())
                && unit.exclusiveArea().compareTo(new java.math.BigDecimal("54.26")) == 0);
    }

    @Test
    void 두_줄_헤더에서_전용면적과_임대조건을_읽는다() throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var first = sheet.createRow(0);
            first.createCell(0).setCellValue("주소");
            first.createCell(1).setCellValue("면적(㎡)");
            first.createCell(2).setCellValue("임대조건(원)");
            var second = sheet.createRow(1);
            second.createCell(1).setCellValue("전용");
            second.createCell(2).setCellValue("임대보증금");
            second.createCell(3).setCellValue("월임대료");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("인천광역시 미추홀구 인주대로 1");
            row.createCell(1).setCellValue(51.83);
            row.createCell(2).setCellValue(6375000);
            row.createCell(3).setCellValue(189580);
            workbook.write(output);
            var result = parser.parse(output.toByteArray(), new LhAttachment("test", "목록.xlsx"));
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().exclusiveArea()).isEqualByComparingTo("51.83");
            assertThat(result.getFirst().rentDeposit()).isEqualTo(6375000L);
            assertThat(result.getFirst().monthlyRent()).isEqualTo(189580L);
        }
    }

    @Test
    void 헤더_이름을_기준으로_주택을_읽는다() throws Exception {
        byte[] workbookBytes = LhSpreadsheetTestFixture.oneHousingWorkbook();

        List<DetailedHousingUnit> units = parser.parse(workbookBytes, new LhAttachment("10", "공급주택목록.xlsx"));

        assertThat(units).hasSize(1);
        DetailedHousingUnit unit = units.getFirst();
        assertThat(unit.address()).isEqualTo("강원특별자치도 춘천시 효자문길 38 (효자동)");
        assertThat(unit.buildingDong()).isEqualTo("101동");
        assertThat(unit.unitNumber()).isEqualTo("202");
        assertThat(unit.exclusiveArea()).isEqualByComparingTo("59.4");
        assertThat(unit.rentDeposit()).isEqualTo(6_913_000L);
        assertThat(unit.monthlyRent()).isEqualTo(135_150L);
    }

    @Test
    void 설정한_실제_LH_샘플을_읽는다() throws Exception {
        String samplePath = System.getenv("LH_XLSX_SAMPLE");
        assumeTrue(samplePath != null && Files.isRegularFile(Path.of(samplePath)));

        List<DetailedHousingUnit> units = parser.parse(
                Files.readAllBytes(Path.of(samplePath)),
                new LhAttachment("68877140", "공급주택목록.xlsx")
        );

        assertThat(units).hasSize(83);
        assertThat(units).allMatch(unit -> unit.address() != null && !unit.address().isBlank());
    }
}
