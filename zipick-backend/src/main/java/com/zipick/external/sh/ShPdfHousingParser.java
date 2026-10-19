package com.zipick.external.sh;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import com.zipick.external.housingdetail.DetailedRentalCondition;
import com.zipick.external.housingdetail.LhAttachment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShPdfHousingParser {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Pattern ROW = Pattern.compile(
            "^(?<row>\\d+)\\s+(?<district>\\S+구)\\s+(?<prefix>.+?)\\s+"
                    + "(?<lot>\\S+구\\s+[가-힣A-Za-z0-9·.\\-]+(?:동|가)\\s+\\d+(?:-\\d+)?)\\s+"
                    + "(?<road>\\S+구\\s+.+?)\\s+"
                    + "(?<displayUnit>(?:(?:\\d+|[가-힣])동\\s+)?\\d+호)\\s+"
                    + "(?<unit>\\d{4})\\s+(?<area>\\d+(?:\\.\\d+)?)\\s+"
                    + "(?<rooms>\\d+)\\s+(?<type>일반형|전세형)\\s+"
                    + "(?<status>신규|재공급)\\s+(?<tail>.+)$"
    );
    private static final Pattern TYPE_AT_END = Pattern.compile("^(?<name>.+?)\\s+(?<housingType>\\d+[A-Za-z]+)$");
    private static final Pattern MONEY_AND_FACILITIES = Pattern.compile(
            "^(?<deposit1>[\\d,]+)\\s+(?<rent1>[\\d,]+)\\s+"
                    + "(?<deposit2>[\\d,]+)\\s+(?<rent2>[\\d,]+)\\s+"
                    + "(?<station>.+?역)\\s+(?<distance>\\d+(?:\\.\\d+)?)\\s+"
                    + "(?<elevator>[OX])\\s+(?<community>[OX])\\s+"
                    + "(?<parking>\\d+(?:\\(\\d+\\))?)\\s+(?<mechanical>[OX])(?:\\s+.*)?$"
    );
    private static final Pattern PARKING = Pattern.compile("(?<total>\\d+)(?:\\((?<mechanical>\\d+)\\))?");

    public List<DetailedHousingUnit> parse(byte[] bytes, ShAttachment attachment) {
        return parse(bytes, attachment.fileSeq(), attachment.oriFileNm(), "SH_FILE");
    }

    public List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment, String sourcePrefix) {
        return parse(bytes, attachment.fileId(), attachment.fileName(), sourcePrefix);
    }

    private List<DetailedHousingUnit> parse(
            byte[] bytes,
            String sourceFileId,
            String fileName,
            String sourcePrefix
    ) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parseText(stripper.getText(document), sourceFileId, sourcePrefix);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("SH 공급주택 PDF를 읽지 못했습니다: " + fileName, exception);
        }
    }

    List<DetailedHousingUnit> parseText(String text, ShAttachment attachment) {
        return parseText(text, attachment.fileSeq(), "SH_FILE");
    }

    private List<DetailedHousingUnit> parseText(String text, String sourceFileId, String sourcePrefix) {
        List<DetailedHousingUnit> units = new ArrayList<>();
        for (String sourceLine : text.split("\\R")) {
            String line = normalizeLine(sourceLine);
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            Matcher type = TYPE_AT_END.matcher(row.group("prefix"));
            Matcher details = MONEY_AND_FACILITIES.matcher(joinSplitMoney(row.group("tail")));
            if (!type.matches() || !details.matches()) {
                continue;
            }

            Integer rowNumber = integer(row.group("row"));
            String district = row.group("district");
            String lotAddress = seoulAddress(row.group("lot"));
            String roadAddress = seoulAddress(row.group("road"));
            String unitNumber = row.group("unit");
            String buildingDong = buildingDong(row.group("displayUnit"));
            Long deposit1 = money(details.group("deposit1"));
            Long rent1 = money(details.group("rent1"));
            Long deposit2 = money(details.group("deposit2"));
            Long rent2 = money(details.group("rent2"));
            Matcher parking = PARKING.matcher(details.group("parking"));
            Integer totalParking = null;
            Integer mechanicalParking = null;
            if (parking.matches()) {
                totalParking = integer(parking.group("total"));
                mechanicalParking = integer(parking.group("mechanical"));
            }

            List<DetailedRentalCondition> conditions = List.of(
                    new DetailedRentalCondition(
                            "SH_INCOME_50_OR_LOWER", "소득 50% 이하 신청자(수급계층 포함)",
                            deposit1, rent1, null
                    ),
                    new DetailedRentalCondition(
                            "SH_INCOME_70_OR_LOWER", "소득 70% 이하 신청자(맞벌이 90% 이하)",
                            deposit2, rent2, null
                    )
            );

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("sourceRowNumber", rowNumber);
            raw.put("district", district);
            raw.put("complexName", type.group("name"));
            raw.put("housingType", type.group("housingType"));
            raw.put("lotAddress", lotAddress);
            raw.put("roadAddress", roadAddress);
            raw.put("unitNumber", unitNumber);
            raw.put("exclusiveArea", row.group("area"));
            raw.put("roomCount", row.group("rooms"));
            raw.put("supplyType", row.group("type"));
            raw.put("supplyStatus", row.group("status"));
            raw.put("nearbySubwayStation", details.group("station"));
            raw.put("subwayWalkDistanceKm", details.group("distance"));
            raw.put("elevatorAvailable", booleanValue(details.group("elevator")));
            raw.put("communityFacilityAvailable", booleanValue(details.group("community")));
            raw.put("totalParkingSpaces", totalParking);
            raw.put("mechanicalParkingAvailable", booleanValue(details.group("mechanical")));
            raw.put("mechanicalParkingSpaces", mechanicalParking);

            units.add(new DetailedHousingUnit(
                    sourcePrefix + ":" + sourceFileId + ":" + rowNumber,
                    type.group("name"), "서울특별시", district, roadAddress,
                    buildingDong, unitNumber, floor(unitNumber), decimal(row.group("area")),
                    integer(row.group("rooms")), type.group("housingType"), row.group("type"),
                    deposit1, rent1, JSON_MAPPER.writeValueAsString(raw), lotAddress, roadAddress,
                    rowNumber, row.group("status"), details.group("station"),
                    decimal(details.group("distance")), booleanValue(details.group("elevator")),
                    booleanValue(details.group("community")), totalParking,
                    booleanValue(details.group("mechanical")), mechanicalParking, conditions
            ));
        }
        return List.copyOf(units);
    }

    private String normalizeLine(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String joinSplitMoney(String value) {
        return value.replaceAll("(?<=\\b\\d)\\s+(?=\\d{2},\\d{3}(?:\\s|$))", "");
    }

    private String seoulAddress(String value) {
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.startsWith("서울") ? normalized : "서울특별시 " + normalized;
    }

    private String buildingDong(String value) {
        Matcher matcher = Pattern.compile("^(.+?)동\\s+\\d+호$").matcher(value);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private Integer floor(String unitNumber) {
        try {
            return Integer.parseInt(unitNumber) / 100;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanValue(String value) {
        return "O".equalsIgnoreCase(value) ? Boolean.TRUE
                : "X".equalsIgnoreCase(value) ? Boolean.FALSE : null;
    }

    private Integer integer(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long money(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
