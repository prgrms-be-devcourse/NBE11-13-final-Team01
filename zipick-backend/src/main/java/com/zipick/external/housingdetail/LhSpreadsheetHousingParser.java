package com.zipick.external.housingdetail;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LhSpreadsheetHousingParser {

    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.KOREA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<DetailedHousingUnit> units = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                parseSheet(sheet, sheetIndex, attachment, formatter, evaluator, units);
            }
            return List.copyOf(units);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("LH 공급주택 엑셀을 읽지 못했습니다: " + attachment.fileName(), exception);
        }
    }

    private void parseSheet(
            Sheet sheet,
            int sheetIndex,
            LhAttachment attachment,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            List<DetailedHousingUnit> units
    ) {
        Header header = findHeader(sheet, formatter, evaluator);
        if (header == null) {
            return;
        }

        int consecutiveBlankRows = 0;
        for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            String address = value(row, header.address(), formatter, evaluator);
            if (!StringUtils.hasText(address)) {
                if (++consecutiveBlankRows >= 10) {
                    break;
                }
                continue;
            }
            consecutiveBlankRows = 0;

            Map<String, String> raw = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : header.columns().entrySet()) {
                raw.put(entry.getKey(), value(row, entry.getValue(), formatter, evaluator));
            }

            String unitNumber = value(row, header.column("호", "호수"), formatter, evaluator);
            String buildingDong = value(row, header.column("동", "동명"), formatter, evaluator);
            String sequence = value(row, header.column("순번", "번호", "연번"), formatter, evaluator);
            String sourceKey = "LH_FILE:" + attachment.fileId() + ":" + sheetIndex + ":"
                    + (StringUtils.hasText(sequence) ? sequence : rowIndex);

            units.add(new DetailedHousingUnit(
                    sourceKey,
                    value(row, header.column(
                            "단지명", "주택명", "건물명", "주택명칭", "단지(주택)명", "소재지명", "명"
                    ), formatter, evaluator),
                    addressPart(address, 0),
                    addressPart(address, 1),
                    normalizeAddress(address),
                    buildingDong,
                    unitNumber,
                    integer(value(row, header.column("층수", "층"), formatter, evaluator)),
                    decimal(value(row, header.column("전용면적", "전용"), formatter, evaluator)),
                    integer(value(row, header.column("방수", "방개수"), formatter, evaluator)),
                    firstText(
                            value(row, header.column("주택유형"), formatter, evaluator),
                            value(row, header.column("공급형", "주택형"), formatter, evaluator)
                    ),
                    value(row, header.column("공급유형", "공급구분"), formatter, evaluator),
                    longValue(value(row, header.column("임대보증금", "보증금"), formatter, evaluator)),
                    longValue(value(row, header.column("월임대료", "월임대료(원)", "임대료"), formatter, evaluator)),
                    JSON_MAPPER.writeValueAsString(raw)
            ));
        }
    }

    private Header findHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int last = Math.min(sheet.getLastRowNum(), 50);
        for (int rowIndex = 0; rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                String label = normalizeHeader(value(row, columnIndex, formatter, evaluator));
                if (StringUtils.hasText(label)) {
                    columns.putIfAbsent(label, columnIndex);
                }
            }
            Integer addressColumn = find(columns, "주소", "소재지", "도로명주소", "주택주소");
            if (addressColumn != null) {
                return new Header(rowIndex, addressColumn, columns);
            }
        }
        return null;
    }

    private String value(Row row, Integer column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || column == null || column < 0) {
            return null;
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            return null;
        }
        String value = formatter.formatCellValue(cell, evaluator);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeHeader(String value) {
        return value == null ? null : value.replaceAll("[\\s·_-]", "").replace("(원)", "");
    }

    private String normalizeAddress(String address) {
        return address.trim().replaceAll("\\s+", " ");
    }

    private String addressPart(String address, int index) {
        String[] parts = normalizeAddress(address).split(" ");
        return parts.length > index ? parts[index] : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private Integer integer(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = INTEGER_PATTERN.matcher(value.replace(",", ""));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9-]", "");
        if (!StringUtils.hasText(digits) || "-".equals(digits)) {
            return null;
        }
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replace(",", "").replaceAll("[^0-9.-]", "");
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer find(Map<String, Integer> columns, String... names) {
        for (String name : names) {
            Integer column = columns.get(normalizeHeader(name));
            if (column != null) {
                return column;
            }
        }
        return null;
    }

    private record Header(int rowIndex, int address, Map<String, Integer> columns) {
        Integer column(String... names) {
            for (String name : names) {
                Integer column = columns.get(name.replaceAll("[\\s·_-]", "").replace("(원)", ""));
                if (column != null) {
                    return column;
                }
            }
            return null;
        }
    }
}
