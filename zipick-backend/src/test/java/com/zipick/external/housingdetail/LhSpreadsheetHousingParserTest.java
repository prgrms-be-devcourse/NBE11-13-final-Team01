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
