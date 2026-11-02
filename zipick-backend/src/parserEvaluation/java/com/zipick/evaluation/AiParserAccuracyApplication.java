package com.zipick.evaluation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 저장된 AI 결과를 사람이 PDF 원문으로 확인한 표본 정답과 비교한다. */
public final class AiParserAccuracyApplication {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path RESOURCE_ROOT = Path.of(
            "zipick-backend", "src", "parserEvaluation", "resources", "parser-evaluation"
    );
    private static final Path WORK_ROOT = Path.of(".parser-evaluation");
    private static final Path RESULT_ROOT = WORK_ROOT.resolve("results");

    private AiParserAccuracyApplication() {
    }

    public static void main(String[] args) throws Exception {
        JsonNode manifest = JSON.readTree(Files.readString(
                RESOURCE_ROOT.resolve("manifest.json"), StandardCharsets.UTF_8
        ));
        Map<String, JsonNode> documents = new HashMap<>();
        manifest.path("documents").forEach(node -> documents.put(node.path("id").asText(), node));

        Map<String, Path> resultFiles = selectLatestResults();
        Totals totals = new Totals();
        List<Map<String, Object>> reports = new ArrayList<>();
        for (Map.Entry<String, Path> entry : resultFiles.entrySet()) {
            JsonNode document = documents.get(entry.getKey());
            if (document == null) continue;
            reports.add(evaluate(document, entry.getValue(), totals));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("documents", reports);
        report.put("summary", totals.asMap());
        Files.createDirectories(RESULT_ROOT);
        Files.writeString(RESULT_ROOT.resolve("ai-accuracy.json"),
                JSON.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(RESULT_ROOT.resolve("ai-accuracy.csv"),
                totals.toCsv(), StandardCharsets.UTF_8);

        System.out.println("AI parser accuracy evaluation complete");
        System.out.println("evaluatedDocuments=" + reports.size());
        System.out.println("sampleFieldAccuracy=" + percent(totals.correctFields, totals.assertedFields));
        System.out.println("sampleMissingRate=" + percent(totals.missingFields, totals.assertedFields));
        System.out.println("sampleWrongRate=" + percent(totals.wrongFields, totals.assertedFields));
        System.out.println("evidenceMatchRate=" + percent(totals.validEvidence, totals.checkedEvidence));
        System.out.println("additionalAttributeRecall="
                + percent(totals.foundAttributes, totals.expectedAttributes));
        System.out.println("report=" + RESULT_ROOT.resolve("ai-accuracy.json").toAbsolutePath());
    }

    private static Map<String, Path> selectLatestResults() throws Exception {
        String configured = System.getenv("PARSER_RESULT");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            return Map.of(root.path("documentId").asText(), path);
        }
        Map<String, Path> selected = new LinkedHashMap<>();
        if (!Files.exists(RESULT_ROOT)) return selected;
        try (var files = Files.list(RESULT_ROOT)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("baseline.json"))
                    .filter(path -> !path.getFileName().toString().equals("ai-accuracy.json"))
                    .sorted(Comparator.comparingLong(AiParserAccuracyApplication::lastModified))
                    .forEach(path -> {
                        try {
                            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
                            String id = root.path("documentId").asText();
                            if (!id.isBlank() && root.path("units").isArray()) selected.put(id, path);
                        } catch (Exception ignored) {
                            // 평가 결과가 아닌 JSON은 건너뛴다.
                        }
                    });
        }
        return selected;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception exception) {
            return 0L;
        }
    }

    private static Map<String, Object> evaluate(JsonNode document, Path resultFile, Totals totals) throws Exception {
        JsonNode actualRoot = JSON.readTree(Files.readString(resultFile, StandardCharsets.UTF_8));
        JsonNode truth = JSON.readTree(Files.readString(
                RESOURCE_ROOT.resolve(document.path("groundTruthFile").asText()), StandardCharsets.UTF_8
        ));
        List<JsonNode> actualUnits = new ArrayList<>();
        actualRoot.path("units").forEach(actualUnits::add);
        Map<Integer, String> pageTexts = pageTexts(
                WORK_ROOT.resolve("documents").resolve(document.path("localFile").asText())
        );

        int expectedCount = document.path("expectedUnitCount").asInt();
        int parsedCount = actualUnits.size();
        totals.expectedUnits += expectedCount;
        totals.parsedUnits += parsedCount;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (JsonNode expected : truth.path("units")) {
            JsonNode actual = bestMatch(expected, actualUnits);
            totals.expectedSamples++;
            if (actual != null) totals.matchedSamples++;
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : expected.path("fields").properties()) {
                String expectedValue = field.getValue().asText();
                String actualValue = actual == null ? "" : actualField(actual, field.getKey());
                String status;
                if (actualValue.isBlank()) {
                    status = "MISSING";
                    totals.missingFields++;
                } else if (equivalent(field.getKey(), expectedValue, actualValue)) {
                    status = "CORRECT";
                    totals.correctFields++;
                } else {
                    status = "WRONG";
                    totals.wrongFields++;
                }
                totals.assertedFields++;
                fields.put(field.getKey(), Map.of(
                        "expected", expectedValue, "actual", actualValue, "status", status
                ));
            }
            EvidenceCheck evidence = actual == null ? new EvidenceCheck(false, 0, "")
                    : verifyEvidence(actual, pageTexts);
            if (actual != null) {
                totals.checkedEvidence++;
                if (evidence.valid()) totals.validEvidence++;
            }
            samples.add(Map.of(
                    "key", expected.path("key").asText(),
                    "matched", actual != null,
                    "fields", fields,
                    "evidencePage", evidence.page(),
                    "evidence", evidence.text(),
                    "evidenceFoundInPdf", evidence.valid()
            ));
        }

        List<Attribute> actualAttributes = collectAttributes(actualRoot);
        int foundAttributes = 0;
        List<Map<String, Object>> attributeResults = new ArrayList<>();
        for (JsonNode expected : truth.path("additionalAttributes")) {
            boolean found = actualAttributes.stream().anyMatch(actual ->
                    equivalentAttributeName(expected.path("name").asText(), actual.name())
                            && equivalentText(expected.path("value").asText(), actual.value()));
            if (found) foundAttributes++;
            attributeResults.add(Map.of(
                    "name", expected.path("name").asText(),
                    "value", expected.path("value").asText(),
                    "found", found
            ));
        }
        totals.expectedAttributes += truth.path("additionalAttributes").size();
        totals.foundAttributes += foundAttributes;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("documentId", document.path("id").asText());
        report.put("resultFile", resultFile.getFileName().toString());
        report.put("complete", actualRoot.path("complete").asBoolean(false));
        report.put("expectedUnitCount", expectedCount);
        report.put("parsedUnitCount", parsedCount);
        report.put("unitCountMatched", expectedCount == parsedCount);
        report.put("sampleCount", truth.path("units").size());
        report.put("sampleResults", samples);
        report.put("additionalAttributeResults", attributeResults);
        return report;
    }

    private static JsonNode bestMatch(JsonNode expected, List<JsonNode> actualUnits) {
        String expectedUnit = normalizeUnitNumber(expected.path("fields").path("unitNumber").asText());
        int expectedRow = expectedRow(expected.path("key").asText());
        return actualUnits.stream()
                .filter(actual -> normalizeUnitNumber(actual.path("unitNumber").asText()).equals(expectedUnit))
                .filter(actual -> {
                    int actualRow = sourceRow(actual);
                    return actualRow <= 0 || expectedRow <= 0 || actualRow == expectedRow;
                })
                .filter(actual -> sourceRow(actual) > 0 || matchScore(expected, actual) >= 3)
                .max(Comparator.comparingInt(actual -> matchScore(expected, actual)))
                .orElse(null);
    }

    private static String normalizeUnitNumber(String value) {
        return normalize(value).replaceFirst("(?:호실|호)$", "");
    }

    private static int sourceRow(JsonNode actual) {
        int direct = actual.path("sourceRowNumber").asInt(0);
        if (direct > 0) return direct;
        JsonNode raw = rawPayload(actual);
        int rawNumber = raw.path("sourceRowNumber").asInt(0);
        if (rawNumber > 0) return rawNumber;
        String evidence = raw.path("evidence").asText("");
        if (evidence.isBlank() && raw.path("rawEvidence").isArray() && !raw.path("rawEvidence").isEmpty()) {
            evidence = raw.path("rawEvidence").get(0).path("text").asText("");
        }
        return leadingNumber(evidence);
    }

    private static int leadingNumber(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\s*(\\d+)").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static int expectedRow(String key) {
        if (key == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)-").matcher(key);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static int matchScore(JsonNode expected, JsonNode actual) {
        int score = 0;
        JsonNode fields = expected.path("fields");
        if (equivalentText(fields.path("complexName").asText(), actual.path("complexName").asText())) score += 3;
        if (equivalent("address", fields.path("address").asText(), actualField(actual, "address"))) score += 3;
        if (equivalentText(fields.path("buildingDong").asText(), actual.path("buildingDong").asText())) score++;
        return score;
    }

    private static String actualField(JsonNode actual, String name) {
        if ("address".equals(name)) {
            String direct = actual.path("address").asText("");
            String province = actual.path("provinceName").asText("");
            String district = actual.path("districtName").asText("");
            List<String> parts = new ArrayList<>();
            if (!province.isBlank() && !normalize(direct).contains(normalize(province))) parts.add(province);
            if (!district.isBlank() && !normalize(direct).contains(normalize(district))) parts.add(district);
            if (!direct.isBlank()) parts.add(direct);
            return String.join(" ", parts);
        }
        JsonNode value = actual.path(name);
        if (!value.isMissingNode() && !value.isNull()) return value.asText();
        JsonNode raw = rawPayload(actual);
        value = raw.path(name);
        if (!value.isMissingNode() && !value.isNull()) return value.asText();
        if ("elevatorAvailable".equals(name)) {
            for (Attribute attribute : collectAttributes(raw)) {
                if (normalize(attribute.name()).contains("승강기")) {
                    String normalized = normalize(attribute.value());
                    if (normalized.equals("유") || normalized.equals("있음") || normalized.equals("가능")) return "true";
                    if (normalized.equals("무") || normalized.equals("없음") || normalized.equals("불가")) return "false";
                }
            }
        }
        return "";
    }

    private static JsonNode rawPayload(JsonNode unit) {
        String raw = unit.path("rawPayload").asText("");
        if (raw.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(raw);
        } catch (Exception exception) {
            return JSON.createObjectNode();
        }
    }

    private static EvidenceCheck verifyEvidence(JsonNode unit, Map<Integer, String> pageTexts) {
        JsonNode raw = rawPayload(unit);
        int page = raw.path("page").asInt(0);
        String evidence = raw.path("evidence").asText("");
        if (evidence.isBlank() && raw.path("rawEvidence").isArray() && !raw.path("rawEvidence").isEmpty()) {
            JsonNode item = raw.path("rawEvidence").get(0);
            page = item.path("page").asInt(0);
            evidence = item.path("text").asText("");
        }
        String pageText = pageTexts.getOrDefault(page, "");
        String compactEvidence = normalize(evidence);
        boolean valid = page > 0 && !compactEvidence.isBlank()
                && normalize(pageText).contains(compactEvidence);
        return new EvidenceCheck(valid, page, evidence);
    }

    private static Map<Integer, String> pageTexts(Path pdf) throws Exception {
        Map<Integer, String> result = new HashMap<>();
        if (!Files.exists(pdf)) return result;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                result.put(page, stripper.getText(document));
            }
        }
        return result;
    }

    private static List<Attribute> collectAttributes(JsonNode root) {
        List<Attribute> result = new ArrayList<>();
        collectAttributes(root, result);
        return result;
    }

    private static void collectAttributes(JsonNode node, List<Attribute> target) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            if (node.has("name") && node.has("value")) {
                target.add(new Attribute(node.path("name").asText(), node.path("value").asText()));
            }
            node.properties().forEach(entry -> collectAttributes(entry.getValue(), target));
        } else if (node.isArray()) {
            node.forEach(value -> collectAttributes(value, target));
        } else if (node.isTextual() && node.asText().stripLeading().startsWith("{")) {
            try {
                collectAttributes(JSON.readTree(node.asText()), target);
            } catch (Exception ignored) {
                // 일반 문자열은 속성 JSON이 아니다.
            }
        }
    }

    private static boolean equivalent(String field, String expected, String actual) {
        if (expected == null || expected.isBlank()) return actual == null || actual.isBlank();
        if ("address".equals(field)) {
            String left = normalizeAddress(expected);
            String right = normalizeAddress(actual);
            return left.equals(right) || left.endsWith(right) || right.endsWith(left);
        }
        if (List.of("exclusiveArea", "rentDeposit", "monthlyRent", "floor", "roomCount",
                "totalParkingSpaces").contains(field)) {
            try {
                return new BigDecimal(number(expected)).compareTo(new BigDecimal(number(actual))) == 0;
            } catch (Exception ignored) {
                return false;
            }
        }
        if ("housingType".equals(field)) {
            return normalizeHousingType(expected).equals(normalizeHousingType(actual));
        }
        return equivalentText(expected, actual);
    }

    private static boolean equivalentText(String expected, String actual) {
        if (expected == null || expected.isBlank()) return actual == null || actual.isBlank();
        String left = normalize(expected);
        String right = normalize(actual);
        return left.equals(right) || (!left.isBlank() && (left.contains(right) || right.contains(left)));
    }

    private static boolean equivalentAttributeName(String expected, String actual) {
        String left = normalize(expected)
                .replace("주거공용면적", "공용면적")
                .replace("면적계", "임대면적");
        String right = normalize(actual)
                .replace("주거공용면적", "공용면적")
                .replace("면적계", "임대면적");
        return left.equals(right) || (!left.isBlank() && (left.contains(right) || right.contains(left)));
    }

    private static String normalizeHousingType(String value) {
        return normalize(value).replace("도시형생활주택", "도시형");
    }

    private static String normalizeAddress(String value) {
        return normalize(value).replace("서울시", "서울특별시");
    }

    private static String number(String value) {
        return value.replaceAll("[^0-9.\\-]", "");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]", "");
    }

    private static String percent(long numerator, long denominator) {
        return denominator == 0 ? "N/A" : String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
    }

    private record Attribute(String name, String value) {
    }

    private record EvidenceCheck(boolean valid, int page, String text) {
    }

    private static final class Totals {
        private long expectedUnits;
        private long parsedUnits;
        private long assertedFields;
        private long correctFields;
        private long missingFields;
        private long wrongFields;
        private long checkedEvidence;
        private long validEvidence;
        private long expectedAttributes;
        private long foundAttributes;
        private long expectedSamples;
        private long matchedSamples;

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expectedUnits", expectedUnits);
            result.put("parsedUnits", parsedUnits);
            result.put("sampleUnitMatchRate", percent(matchedSamples, expectedSamples));
            result.put("assertedSampleFields", assertedFields);
            result.put("correctSampleFields", correctFields);
            result.put("sampleFieldAccuracy", percent(correctFields, assertedFields));
            result.put("sampleMissingRate", percent(missingFields, assertedFields));
            result.put("sampleWrongRate", percent(wrongFields, assertedFields));
            result.put("evidenceMatchRate", percent(validEvidence, checkedEvidence));
            result.put("additionalAttributeRecall", percent(foundAttributes, expectedAttributes));
            result.put("warning", "대형 문서는 표본 필드 정확도이며 전 행 정확도를 의미하지 않습니다.");
            return result;
        }

        String toCsv() {
            return "metric,value\n"
                    + "expected_units," + expectedUnits + "\n"
                    + "parsed_units," + parsedUnits + "\n"
                    + "sample_unit_match_rate," + percent(matchedSamples, expectedSamples) + "\n"
                    + "sample_field_accuracy," + percent(correctFields, assertedFields) + "\n"
                    + "sample_missing_rate," + percent(missingFields, assertedFields) + "\n"
                    + "sample_wrong_rate," + percent(wrongFields, assertedFields) + "\n"
                    + "evidence_match_rate," + percent(validEvidence, checkedEvidence) + "\n"
                    + "additional_attribute_recall," + percent(foundAttributes, expectedAttributes) + "\n";
        }
    }
}
