package com.zipick.evaluation;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import com.zipick.external.housingdetail.LhAttachment;
import com.zipick.external.housingdetail.LhPdfHousingParser;
import com.zipick.external.sh.ShPdfHousingParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 공식 PDF 원문으로 만든 정답셋과 현재 정규식 파서를 비교한다.
 * 외부 다운로드가 있으므로 일반 테스트와 CI에서는 실행하지 않는다.
 */
public final class ParserEvaluationApplication {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path RESOURCE_ROOT = Path.of(
            "zipick-backend", "src", "parserEvaluation", "resources", "parser-evaluation"
    );
    private static final Path WORK_ROOT = Path.of(".parser-evaluation");
    private static final Path DOCUMENT_ROOT = WORK_ROOT.resolve("documents");
    private static final Path RESULT_ROOT = WORK_ROOT.resolve("results");

    private ParserEvaluationApplication() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DOCUMENT_ROOT);
        Files.createDirectories(RESULT_ROOT);

        JsonNode manifest = JSON.readTree(Files.readString(
                RESOURCE_ROOT.resolve("manifest.json"), StandardCharsets.UTF_8
        ));
        List<Map<String, Object>> reportDocuments = new ArrayList<>();
        Totals totals = new Totals();

        for (JsonNode document : manifest.path("documents")) {
            reportDocuments.add(evaluate(document, totals));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("documents", reportDocuments);
        report.put("summary", totals.asMap());
        String json = JSON.writeValueAsString(report);
        Files.writeString(RESULT_ROOT.resolve("baseline.json"), json, StandardCharsets.UTF_8);
        Files.writeString(RESULT_ROOT.resolve("comparison.csv"), totals.toCsv(), StandardCharsets.UTF_8);

        System.out.println("PDF parser evaluation complete");
        System.out.println("documents=" + reportDocuments.size());
        System.out.println("expectedUnits=" + totals.expectedUnits + ", parsedUnits=" + totals.parsedUnits);
        System.out.println("fieldAccuracy=" + percent(totals.correctFields, totals.assertedFields));
        System.out.println("missingRate=" + percent(totals.missingFields, totals.assertedFields));
        System.out.println("wrongRate=" + percent(totals.wrongFields, totals.assertedFields));
        System.out.println("additionalAttributeRecall="
                + percent(totals.foundAdditionalAttributes, totals.expectedAdditionalAttributes));
        System.out.println("report=" + RESULT_ROOT.toAbsolutePath());
    }

    private static Map<String, Object> evaluate(JsonNode document, Totals totals) throws Exception {
        String id = document.path("id").asText();
        Path pdf = DOCUMENT_ROOT.resolve(document.path("localFile").asText());
        downloadIfMissing(document.path("sourceUrl").asText(), pdf);

        byte[] bytes = Files.readAllBytes(pdf);
        long started = System.nanoTime();
        List<DetailedHousingUnit> units = parse(document.path("parser").asText(), bytes, pdf.getFileName().toString());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        JsonNode truth = JSON.readTree(Files.readString(
                RESOURCE_ROOT.resolve(document.path("groundTruthFile").asText()), StandardCharsets.UTF_8
        ));
        int expectedCount = document.path("expectedUnitCount").asInt();
        totals.expectedUnits += expectedCount;
        totals.parsedUnits += units.size();

        List<Map<String, Object>> unitResults = compareUnits(truth.path("units"), units, totals);
        int additionalCount = truth.path("additionalAttributes").size();
        totals.expectedAdditionalAttributes += additionalCount;
        // 현재 정규식 결과 모델에는 임의 속성 컬렉션이 없으므로 발견 수는 0이다.

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("agency", document.path("agency").asText());
        result.put("title", document.path("title").asText());
        result.put("expectedUnitCount", expectedCount);
        result.put("parsedUnitCount", units.size());
        result.put("elapsedMillis", elapsedMillis);
        result.put("unitResults", unitResults);
        result.put("expectedAdditionalAttributeCount", additionalCount);
        result.put("parsedAdditionalAttributeCount", 0);
        result.put("parserOutput", units.stream().map(ParserEvaluationApplication::toMap).toList());
        return result;
    }

    private static List<DetailedHousingUnit> parse(String parser, byte[] bytes, String fileName) {
        LhAttachment attachment = new LhAttachment(fileName, fileName);
        LhPdfHousingParser lh = new LhPdfHousingParser();
        if ("LH_ADDRESS".equals(parser)) {
            return lh.parse(bytes, attachment);
        }
        ShPdfHousingParser sh = new ShPdfHousingParser();
        List<DetailedHousingUnit> structured = sh.parse(bytes, attachment, "EVAL_SH");
        if ("SH_TABLE".equals(parser) || !structured.isEmpty()) {
            return structured;
        }
        return lh.parse(bytes, attachment);
    }

    private static List<Map<String, Object>> compareUnits(
            JsonNode expectedUnits,
            List<DetailedHousingUnit> actualUnits,
            Totals totals
    ) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode expected : expectedUnits) {
            String unitNumber = expected.path("fields").path("unitNumber").asText();
            DetailedHousingUnit actual = actualUnits.stream()
                    .filter(unit -> unitNumber.equals(normalize(unit.unitNumber())))
                    .findFirst()
                    .orElse(null);

            Map<String, Object> fields = new LinkedHashMap<>();
            expected.path("fields").properties().forEach(entry -> {
                String expectedValue = normalize(entry.getValue().asText());
                String actualValue = actual == null ? null : normalize(field(actual, entry.getKey()));
                String status;
                if (actualValue == null || actualValue.isBlank()) {
                    status = "MISSING";
                    totals.missingFields++;
                } else if (equivalent(expectedValue, actualValue)) {
                    status = "CORRECT";
                    totals.correctFields++;
                } else {
                    status = "WRONG";
                    totals.wrongFields++;
                }
                totals.assertedFields++;
                fields.put(entry.getKey(), Map.of(
                        "expected", expectedValue,
                        "actual", actualValue == null ? "" : actualValue,
                        "status", status
                ));
            });
            results.add(Map.of(
                    "key", expected.path("key").asText(),
                    "matched", actual != null,
                    "fields", fields,
                    "evidence", JSON.convertValue(expected.path("evidence"), List.class)
            ));
        }
        return results;
    }

    private static Object field(DetailedHousingUnit unit, String name) {
        return switch (name) {
            case "complexName" -> unit.complexName();
            case "unitNumber" -> unit.unitNumber();
            case "address" -> unit.address();
            case "floor" -> unit.floor();
            case "exclusiveArea" -> unit.exclusiveArea();
            case "roomCount" -> unit.roomCount();
            case "housingType" -> unit.housingType();
            case "supplyType" -> unit.supplyType();
            case "rentDeposit" -> unit.rentDeposit();
            case "monthlyRent" -> unit.monthlyRent();
            case "elevatorAvailable" -> unit.elevatorAvailable();
            case "communityFacilityAvailable" -> unit.communityFacilityAvailable();
            case "totalParkingSpaces" -> unit.totalParkingSpaces();
            default -> null;
        };
    }

    private static Map<String, Object> toMap(DetailedHousingUnit unit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceUnitKey", unit.sourceUnitKey());
        row.put("complexName", unit.complexName());
        row.put("address", unit.address());
        row.put("buildingDong", unit.buildingDong());
        row.put("unitNumber", unit.unitNumber());
        row.put("exclusiveArea", unit.exclusiveArea());
        row.put("rentDeposit", unit.rentDeposit());
        row.put("monthlyRent", unit.monthlyRent());
        row.put("elevatorAvailable", unit.elevatorAvailable());
        row.put("communityFacilityAvailable", unit.communityFacilityAvailable());
        row.put("totalParkingSpaces", unit.totalParkingSpaces());
        return row;
    }

    private static void downloadIfMissing(String sourceUrl, Path target) throws IOException, InterruptedException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "ZIPICK parser evaluation/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("PDF download failed: status=" + response.statusCode() + ", url=" + sourceUrl);
        }
        Files.write(target, response.body());
    }

    private static boolean equivalent(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().toLowerCase(Locale.ROOT)
                .replace("서울시", "서울특별시")
                .replaceAll("[\\s,㎡원]", "");
    }

    private static String percent(long numerator, long denominator) {
        return denominator == 0 ? "0.0%" : String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
    }

    private static final class Totals {
        private long expectedUnits;
        private long parsedUnits;
        private long assertedFields;
        private long correctFields;
        private long missingFields;
        private long wrongFields;
        private long expectedAdditionalAttributes;
        private long foundAdditionalAttributes;

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expectedUnits", expectedUnits);
            result.put("parsedUnits", parsedUnits);
            result.put("assertedFields", assertedFields);
            result.put("correctFields", correctFields);
            result.put("missingFields", missingFields);
            result.put("wrongFields", wrongFields);
            result.put("fieldAccuracy", percent(correctFields, assertedFields));
            result.put("missingRate", percent(missingFields, assertedFields));
            result.put("wrongRate", percent(wrongFields, assertedFields));
            result.put("expectedAdditionalAttributes", expectedAdditionalAttributes);
            result.put("foundAdditionalAttributes", foundAdditionalAttributes);
            result.put("additionalAttributeRecall", percent(
                    foundAdditionalAttributes, expectedAdditionalAttributes
            ));
            return result;
        }

        private String toCsv() {
            return "metric,value\n"
                    + "expected_units," + expectedUnits + "\n"
                    + "parsed_units," + parsedUnits + "\n"
                    + "field_accuracy," + percent(correctFields, assertedFields) + "\n"
                    + "missing_rate," + percent(missingFields, assertedFields) + "\n"
                    + "wrong_rate," + percent(wrongFields, assertedFields) + "\n"
                    + "additional_attribute_recall,"
                    + percent(foundAdditionalAttributes, expectedAdditionalAttributes) + "\n";
        }
    }
}
