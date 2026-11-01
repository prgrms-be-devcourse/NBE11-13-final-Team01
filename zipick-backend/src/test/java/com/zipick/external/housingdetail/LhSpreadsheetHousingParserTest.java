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
