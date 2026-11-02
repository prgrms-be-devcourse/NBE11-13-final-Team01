package com.zipick.external.housingdetail;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** 금액 단위와 계약금 합계가 명확한 표에서만 기본 임대조건을 추출한다. */
public final class RentalTermsPdfParser {
    private static final Pattern ROW = Pattern.compile(
            "(?m)^\\s*(\\d{1,3}[A-Za-z](?:[-][A-Za-z0-9]+)?)\\s+([가-힣A-Za-z0-9()·\\s]{2,40}?)\\s+"
                    + "([0-9][0-9,]*)\\s+([0-9][0-9,]*)\\s+([0-9][0-9,]*)\\s+([0-9][0-9,]*)(?=\\s*\\([+-]\\))");
    private static final Pattern SINGLE_ROW = Pattern.compile(
            "(?m)^\\s*(?:([가-힣A-Za-z0-9]+동)\\s*)?(\\d+호)\\s+(\\d+(?:\\.\\d+)?)\\s+"
                    + "\\d+(?:\\.\\d+)?룸\\s+([0-9][0-9,]*)\\s+([0-9][0-9,]*)\\s+"
                    + "([0-9][0-9,]*)\\s+([0-9][0-9,]*)");
    private static final Pattern SIMPLE_SINGLE_ROW = Pattern.compile(
            "(?m)^\\s*[^\\s]+\\s+(\\d+호)\\s+(\\d+(?:\\.\\d+)?)\\s*(?:㎡|m²)?\\s+"
                    + "\\d+(?:\\.\\d+)?\\s*(?:㎡|m²)?\\s+\\d+(?:\\.\\d+)?룸\\s+"
                    + "(\\d{1,3}(?:,\\d{3}){2,})\\s*(\\d{1,3},\\d{3})(?=\\s|$)");
    private static final Pattern BAND_HEADING = Pattern.compile("【\\s*([0-9A-Za-z,\\s]+)】\\s*구간별\\s*임대조건");
    private static final Pattern BAND_ROW = Pattern.compile("(?m)^\\s*([1-6])\\s*구간\\s+([0-9,]+)\\s+([0-9,]+)\\s+([0-9,]+)\\s+([0-9,]+)\\s*$");

    public record Terms(String housingType, String target, long deposit, long monthlyRent, int page, String evidence) { }
    public record SingleTerms(String buildingDong, String unitNumber, String area, long deposit, long monthlyRent,
                              Long convertedDeposit, Long convertedRent, int page, String evidence, String pageText) { }
    public record IncomeBand(String type, int band, long deposit, long rent, int page, String evidence) { }

    public List<IncomeBand> parseIncomeBands(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            List<IncomeBand> result = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(document);
                var heading = BAND_HEADING.matcher(text);
                if (!heading.find() || !text.contains("임대보증금") || !text.replaceAll("\\s+", "").contains("월임대료")) continue;
                String[] types = heading.group(1).replaceAll("\\s+", "").toUpperCase().split(",");
                if (types.length == 0 || java.util.Arrays.stream(types).anyMatch(type -> !type.matches("\\d{1,3}[A-Z][0-9]*"))) continue;
                List<IncomeBand> pageRows = new ArrayList<>();
                var matcher = BAND_ROW.matcher(text.substring(heading.end()));
                while (matcher.find()) {
                    try {
                        long deposit = Long.parseLong(matcher.group(2).replace(",", ""));
                        long contract = Long.parseLong(matcher.group(3).replace(",", ""));
                        long balance = Long.parseLong(matcher.group(4).replace(",", ""));
                        long rent = Long.parseLong(matcher.group(5).replace(",", ""));
                        if (deposit <= 0 || contract + balance != deposit) continue;
                        for (String type : types) pageRows.add(new IncomeBand(type, Integer.parseInt(matcher.group(1)),
                                deposit, rent, page, matcher.group().trim()));
                    } catch (NumberFormatException ignored) { }
                }
                for (String type : types) {
                    var rows = pageRows.stream().filter(row -> row.type().equals(type)).toList();
                    if (rows.size() == 6 && rows.stream().map(IncomeBand::band).distinct().count() == 6) result.addAll(rows);
                }
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("소득구간 임대조건 PDF를 읽지 못했습니다", exception);
        }
    }

    public List<DetailedHousingUnit> supplementIncomeBands(byte[] bytes, List<DetailedHousingUnit> units) {
        List<IncomeBand> all = parseIncomeBands(bytes);
        if (all.isEmpty()) return units;
        return units.stream().map(unit -> {
            String housingType = unit.housingType() == null ? "" : unit.housingType().replaceAll("\\s+", "").toUpperCase();
            String supplyType = unit.supplyType() == null ? "" : unit.supplyType().replaceAll("\\s+", "").toUpperCase();
            var rows = all.stream().filter(row -> row.type().equals(housingType) || row.type().equals(supplyType)).toList();
            if (rows.size() != 6 || rows.stream().map(IncomeBand::type).distinct().count() != 1) return unit;
            var first = rows.stream().filter(row -> row.band() == 1).findFirst().orElse(null);
            if (first == null || (unit.rentDeposit() != null && unit.rentDeposit() != first.deposit())
                    || (unit.monthlyRent() != null && unit.monthlyRent() != first.rent())) return unit;
            JsonMapper mapper = JsonMapper.builder().build();
            List<DetailedRentalCondition> conditions = new ArrayList<>(unit.rentalConditions());
            for (var row : rows) {
                String code = "INCOME_BAND_" + row.band();
                if (conditions.stream().noneMatch(c -> code.equals(c.conditionCode()))) {
                    conditions.add(new DetailedRentalCondition(code, row.band() + "구간", row.deposit(), row.rent(),
                            mapper.writeValueAsString(Map.of("page", row.page(), "evidence", row.evidence()))));
                }
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("previous", unit.rawPayload());
            raw.put("selectedBaseCondition", "1구간");
            raw.put("verifiedRentalTerms", Map.of("page", first.page(), "evidence", first.evidence(), "type", first.type()));
            return new DetailedHousingUnit(unit.sourceUnitKey(), unit.complexName(), unit.provinceName(), unit.districtName(),
                    unit.address(), unit.buildingDong(), unit.unitNumber(), unit.floor(), unit.exclusiveArea(), unit.roomCount(),
                    unit.housingType(), unit.supplyType(), unit.rentDeposit() == null ? first.deposit() : unit.rentDeposit(),
                    unit.monthlyRent() == null ? first.rent() : unit.monthlyRent(), mapper.writeValueAsString(raw),
                    unit.lotAddress(), unit.roadAddress(), unit.sourceRowNumber(), unit.supplyStatus(), unit.nearbySubwayStation(),
                    unit.subwayWalkDistanceKm(), unit.elevatorAvailable(), unit.communityFacilityAvailable(), unit.totalParkingSpaces(),
                    unit.mechanicalParkingAvailable(), unit.mechanicalParkingSpaces(), unit.supplyCount(), conditions);
        }).toList();
    }

    public List<DetailedHousingUnit> supplementSingleUnits(byte[] bytes, List<DetailedHousingUnit> units) {
        if (units == null || units.size() != 1) return units;
        DetailedHousingUnit unit = units.getFirst();
        if (unit.address() == null || unit.address().isBlank()) return units;
        var rows = parseSingleUnits(bytes);
        if (rows.size() != 1) return units;
        var row = rows.getFirst();
        String address = normalizeAddress(unit.address());
        if (!normalizeAddress(row.pageText()).contains(address) && !hasExplicitPropertyAddress(bytes, address)) return units;
        if ((unit.rentDeposit() != null && unit.rentDeposit() != row.deposit())
                || (unit.monthlyRent() != null && unit.monthlyRent() != row.monthlyRent())) return units;
        if (unit.unitNumber() != null && !unit.unitNumber().replace("호", "").equals(row.unitNumber().replace("호", ""))) return units;
        JsonMapper mapper = JsonMapper.builder().build();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("previous", unit.rawPayload());
        raw.put("verifiedRentalTerms", Map.of("page", row.page(), "evidence", row.evidence()));
        List<DetailedRentalCondition> conditions = new ArrayList<>(unit.rentalConditions());
        if (row.convertedDeposit() != null && row.convertedRent() != null
                && conditions.stream().noneMatch(c -> "MAX_DEPOSIT".equals(c.conditionCode()))) {
            conditions.add(new DetailedRentalCondition("MAX_DEPOSIT", "최대 보증금 전환",
                    row.convertedDeposit(), row.convertedRent(),
                    mapper.writeValueAsString(Map.of("page", row.page(), "evidence", row.evidence()))));
        }
        return List.of(new DetailedHousingUnit(unit.sourceUnitKey(), unit.complexName(), unit.provinceName(),
                unit.districtName(), unit.address(), unit.buildingDong() == null ? row.buildingDong() : unit.buildingDong(),
                unit.unitNumber() == null ? row.unitNumber() : unit.unitNumber(), unit.floor(),
                unit.exclusiveArea() == null ? new java.math.BigDecimal(row.area()) : unit.exclusiveArea(), unit.roomCount(),
                unit.housingType(), unit.supplyType(), unit.rentDeposit() == null ? row.deposit() : unit.rentDeposit(),
                unit.monthlyRent() == null ? row.monthlyRent() : unit.monthlyRent(), mapper.writeValueAsString(raw),
                unit.lotAddress(), unit.roadAddress(), unit.sourceRowNumber(), unit.supplyStatus(),
                unit.nearbySubwayStation(), unit.subwayWalkDistanceKm(), unit.elevatorAvailable(),
                unit.communityFacilityAvailable(), unit.totalParkingSpaces(), unit.mechanicalParkingAvailable(),
                unit.mechanicalParkingSpaces(), unit.supplyCount(), conditions));
    }

    private String normalizeAddress(String text) {
        return text.replaceAll("[\\s\\u00a0]+", "").replace("서울특별시", "서울").replace("서울시", "서울");
    }

    private boolean hasExplicitPropertyAddress(byte[] bytes, String address) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = normalizeAddress(stripper.getText(document));
            return Pattern.compile("(?:건물위치|주택위치|소재지)[:：]?" + Pattern.quote(address))
                    .matcher(text).find();
        } catch (IOException exception) {
            return false;
        }
    }

    public List<SingleTerms> parseSingleUnits(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            List<SingleTerms> result = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                result.addAll(parseSinglePage(stripper.getText(document), page));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("임대조건 PDF를 읽지 못했습니다", exception);
        }
    }

    static List<SingleTerms> parseSinglePage(String text, int page) {
        String compact = text.replaceAll("[\\s\\u00a0]+", "");
        var result = new ArrayList<SingleTerms>();
        if (compact.contains("보증금(원)") && compact.contains("임대료(원)")
                && compact.contains("전용면적") && compact.contains("공급면적")) {
            var simple = SIMPLE_SINGLE_ROW.matcher(text);
            while (simple.find()) {
                try {
                    long deposit = Long.parseLong(simple.group(3).replace(",", ""));
                    long rent = Long.parseLong(simple.group(4).replace(",", ""));
                    result.add(new SingleTerms(null, simple.group(1), simple.group(2), deposit, rent,
                            null, null, page, simple.group().trim(), text));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (!compact.contains("기본임대") || !compact.contains("최대임대")
                || !compact.contains("보증금") || !compact.contains("월임대료")) return List.copyOf(result);
        var matcher = SINGLE_ROW.matcher(text);
        while (matcher.find()) {
            try {
                long deposit = Long.parseLong(matcher.group(4).replace(",", ""));
                long rent = Long.parseLong(matcher.group(5).replace(",", ""));
                long convertedDeposit = Long.parseLong(matcher.group(6).replace(",", ""));
                long convertedRent = Long.parseLong(matcher.group(7).replace(",", ""));
                if (deposit <= 0 || convertedDeposit < deposit || convertedRent > rent) continue;
                result.add(new SingleTerms(matcher.group(1), matcher.group(2), matcher.group(3), deposit, rent,
                        convertedDeposit, convertedRent, page, matcher.group().trim(), text));
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(result);
    }

    public List<Terms> parse(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            List<Terms> result = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                result.addAll(parsePage(stripper.getText(document), page));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("임대조건 PDF를 읽지 못했습니다", exception);
        }
    }

    static List<Terms> parsePage(String text, int page) {
        String compact = text.replaceAll("[\\s\\u00a0]+", "");
        if (!compact.matches("(?s).*임대보증금.{0,15}천원.*")
                || !compact.contains("월임대료") || !compact.contains("(원)")) return List.of();
        List<Terms> result = new ArrayList<>();
        var matcher = ROW.matcher(text);
        while (matcher.find()) {
            try {
                long deposit = Long.parseLong(matcher.group(3).replace(",", ""));
                long contract = Long.parseLong(matcher.group(4).replace(",", ""));
                long balance = Long.parseLong(matcher.group(5).replace(",", ""));
                long rent = Long.parseLong(matcher.group(6).replace(",", ""));
                if (deposit <= 0 || contract + balance != deposit || rent < 0) continue;
                result.add(new Terms(matcher.group(1).toUpperCase(), matcher.group(2).trim(),
                        Math.multiplyExact(deposit, 1000L), rent, page, matcher.group().trim()));
            } catch (NumberFormatException | ArithmeticException ignored) { }
        }
        return List.copyOf(result);
    }
}
