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

/**
 * SH 주택목록(상세) PDF 표를 읽는다.
 *
 * <p>⚠️ <b>이 표 형식을 쓰는 다른 기관 문서도 그대로 매칭된다.</b> LH·GBDC 상세 수집이 PDF 를 받으면
 * 이 파서를 먼저 돌려 보고, 결과가 없을 때만 기관별 파서로 넘어간다.
 * 그래서 <b>시도를 파서가 정해서는 안 된다</b> — 이 표에는 시도 칸이 없고 시·군·구만 있기 때문이다.
 * '구'로 끝나는 시군구는 서울 밖에도 많다(경북 포항시 북구·남구, 부산·대구·인천·광주·대전·울산의 자치구,
 * 천안시 서북구 같은 일반구). 실제로 "3 북구 … 일반형 신규 … 포항역 …" 꼴의 행은 그대로 매칭된다.
 *
 * <p>그래서 시도는 <b>호출한 기관이 알려 준다</b>. SH 공고는 서울특별시 전용이라 서울을 넣고,
 * 같은 표 형식을 빌려 쓰는 다른 기관 문서는 근거가 없으므로 비워 둔다.
 * 비워 두면 RegionNormalizer·지오코딩이 주소를 보고 판단한다 — 없는 값을 지어내지 않는다.
 */
@Component
public class ShPdfHousingParser {

    /** SH 공고는 서울특별시 공급주택만 다룬다. 이 표에는 시도 칸이 없어 기관 정보로 채운다 */
    private static final String SEOUL = "서울특별시";

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
    /** 시도 접미사. "서울특별시" 를 "서울" 로 줄여 주소가 이미 그 시도로 시작하는지 본다 */
    private static final Pattern PROVINCE_SUFFIX =
            Pattern.compile("(?:특별자치시|특별자치도|특별시|광역시|도)$");

    /** SH 공고 첨부. 서울특별시 공급주택이다 */
    public List<DetailedHousingUnit> parse(byte[] bytes, ShAttachment attachment) {
        return parse(bytes, attachment.fileSeq(), attachment.oriFileNm(), "SH_FILE", SEOUL);
    }

    /**
     * SH 가 아닌 기관(LH·GBDC)의 PDF 를 같은 표 형식으로 읽어 본다.
     *
     * <p><b>시도는 비워 둔다.</b> 표에 시도가 없고 기관의 공급 지역도 전국·도 단위라 확정할 수 없다.
     * 여기서 서울을 넣으면 서울 밖 주택이 서울특별시로 저장되는데, 그 값은 표준 시도라
     * RegionNormalizer 가 걸러내지 못하고 지오코딩도 기존 값을 덮지 않아 그대로 굳는다.
     */
    public List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment, String sourcePrefix) {
        return parse(bytes, attachment.fileId(), attachment.fileName(), sourcePrefix, null);
    }

    private List<DetailedHousingUnit> parse(
            byte[] bytes,
            String sourceFileId,
            String fileName,
            String sourcePrefix,
            String province
    ) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parseText(stripper.getText(document), sourceFileId, sourcePrefix, province);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("SH 공급주택 PDF를 읽지 못했습니다: " + fileName, exception);
        }
    }

    List<DetailedHousingUnit> parseText(String text, ShAttachment attachment) {
        return parseText(text, attachment.fileSeq(), "SH_FILE", SEOUL);
    }

    /** 시도를 알 수 없는 기관 문서를 같은 표 형식으로 읽는다(LH·GBDC 경로와 같은 설정) */
    List<DetailedHousingUnit> parseTextWithoutProvince(String text, String sourceFileId, String sourcePrefix) {
        return parseText(text, sourceFileId, sourcePrefix, null);
    }

    private List<DetailedHousingUnit> parseText(
            String text, String sourceFileId, String sourcePrefix, String province
    ) {
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
            String lotAddress = withProvince(province, row.group("lot"));
            String roadAddress = withProvince(province, row.group("road"));
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
                    type.group("name"), province, district, roadAddress,
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

    /**
     * 표에서 읽은 주소 앞에 시도를 붙인다. <b>기관이 알려 준 시도가 있을 때만</b> 붙인다.
     * 시도를 모르면 읽은 그대로 돌려준다 — 주소에 없는 지역명을 지어내지 않는다.
     *
     * <p>주소가 이미 그 시도로 시작하면 덧붙이지 않는다. PDF 에서 "서울강남구 역삼동 1" 처럼
     * 시도와 시군구가 붙어 나오는 줄이 있어, 접미사를 뗀 이름("서울")으로 비교한다.
     */
    private String withProvince(String province, String value) {
        String normalized = value.trim().replaceAll("\s+", " ");
        if (province == null) {
            return normalized;
        }
        String stem = PROVINCE_SUFFIX.matcher(province).replaceFirst("");
        return normalized.startsWith(stem) ? normalized : province + " " + normalized;
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
