package com.zipick.external.housingdetail;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;

final class LhSpreadsheetTestFixture {

    private LhSpreadsheetTestFixture() {
    }

    static byte[] oneHousingWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("주택목록");
            Row header = sheet.createRow(3);
            String[] headers = {"순번", "명", "주소", "동", "호", "공급형", "전용\n면적", "방수", "층수", "주택유형", "임대보증금", "월임대료"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            Row row = sheet.createRow(4);
            Object[] values = {1, "매입다가구", "강원특별자치도 춘천시 효자문길 38 (효자동)", "101동", "202", "2형", 59.4, 2, "2층", "다가구주택", 6913000, 135150};
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Number number) {
                    row.createCell(i).setCellValue(number.doubleValue());
                } else {
                    row.createCell(i).setCellValue(values[i].toString());
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
