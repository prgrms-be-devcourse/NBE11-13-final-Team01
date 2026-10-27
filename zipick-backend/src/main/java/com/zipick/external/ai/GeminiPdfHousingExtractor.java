package com.zipick.external.ai;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import com.zipick.external.housingdetail.DetailedRentalCondition;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PDF의 페이지 선별, 표 해석, 상세주택 추출과 정규화를 Gemini가 한 번에 수행한다. */
@Component
public class GeminiPdfHousingExtractor {

    public static final String PROMPT_VERSION = "DIRECT_HOUSING_V18_PAGED_COMPLETE_COLUMNS";
    private static final int MAX_MISSING_INDEXES_PER_REPAIR = 40;
    private static final int MAX_TRANSIENT_RETRIES = 2;
    private static final Pattern LEADING_ROW_NUMBER = Pattern.compile("(?m)^\\s*(\\d{1,4})(?:\\s+|[.)]\\s+)");
    private static final List<String> SUPPLY_TABLE_MARKERS = List.of("주소", "면적", "호수", "임대");
    private static final List<String> CONSISTENT_UNIT_FIELDS = List.of(
            "complexName", "address",
            "unitNumber", "floor", "exclusiveArea", "commonArea", "rentalArea",
            "roomCount", "housingType", "supplyType", "rentDeposit", "monthlyRent",
            "elevatorAvailable"
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(GeminiPdfHousingExtractor.class);

    private final GeminiPdfParserProperties properties;
    private final GeminiApiTransport transport;
    private final GeminiPdfExtractionCache cache;
    private final GeminiDailyCallBudget budget;
    private final GeminiPdfBatchPlanner batchPlanner;

    @Autowired
    public GeminiPdfHousingExtractor(GeminiPdfParserProperties properties) {
        this(properties, new GeminiHttpTransport(properties),
                new GeminiPdfExtractionCache(properties.cachePath()),
                new GeminiDailyCallBudget(properties.cachePath(), properties.dailyCallLimit(), properties.retryCallReserve()));
    }

    GeminiPdfHousingExtractor(
            GeminiPdfParserProperties properties,
            GeminiApiTransport transport,
            GeminiPdfExtractionCache cache,
            GeminiDailyCallBudget budget
    ) {
        this.properties = properties;
        this.transport = transport;
        this.cache = cache;
        this.budget = budget;
        this.batchPlanner = new GeminiPdfBatchPlanner(properties);
    }

    public GeminiPdfBatchPlan planBatches(List<GeminiPdfDocument> documents) {
        return batchPlanner.plan(documents);
    }

    /** 전체 문서를 자동으로 묶어 처리하고, 할당량·일시 장애 시 미처리 문서를 명시적으로 남긴다. */
    public GeminiPdfBatchExtractionResult extractAll(List<GeminiPdfDocument> documents) {
        GeminiPdfBatchPlan plan = planBatches(documents);
        List<GeminiPdfExtractionResult> results = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        GeminiTokenUsage usage = GeminiTokenUsage.empty();
        int calls = 0;
        for (int batchIndex = 0; batchIndex < plan.batches().size(); batchIndex++) {
            List<GeminiPdfDocument> batch = plan.batches().get(batchIndex);
            try {
                GeminiPdfExtractionResult result = extract(batch);
                results.add(result);
                calls += result.apiCallCount();
                usage = usage.plus(result.tokenUsage());
                if (!result.complete()) warnings.add("묶음 " + (batchIndex + 1) + " 추출 미완료");
            } catch (GeminiDailyQuotaExceededException exception) {
                warnings.add(exception.getMessage());
                appendDeferred(plan, batchIndex, deferred);
                break;
            } catch (RuntimeException exception) {
                warnings.add("묶음 " + (batchIndex + 1) + " 처리 중단: " + exception.getMessage());
                appendDeferred(plan, batchIndex, deferred);
                break;
            }
        }
        boolean complete = deferred.isEmpty()
                && results.size() == plan.batches().size()
                && results.stream().allMatch(GeminiPdfExtractionResult::complete);
        return new GeminiPdfBatchExtractionResult(
                results, plan.batches().size(), results.size(), calls, usage,
                deferred, complete, warnings
        );
    }

    private void appendDeferred(GeminiPdfBatchPlan plan, int startBatch, List<String> target) {
        for (int index = startBatch; index < plan.batches().size(); index++) {
            plan.batches().get(index).stream().map(GeminiPdfDocument::documentId).forEach(target::add);
        }
    }

    public GeminiPdfExtractionResult extract(List<GeminiPdfDocument> documents) {
        validateConfiguration();
        List<InspectedDocument> inspected = inspect(documents);
        String cacheKey = cacheKey(inspected);
        PayloadAccumulator accumulator = new PayloadAccumulator(inspected);
        boolean resumed = false;
        var cached = cache.read(cacheKey);
        if (cached.isPresent()) {
            accumulator.accept(JSON.readTree(cached.get()));
            GeminiPdfExtractionResult result = toResult(
                    accumulator, inspected, 0, true, GeminiTokenUsage.empty(), "CACHE"
            );
            if (result.complete()) {
                log.info("Gemini PDF 캐시 사용: documents={}, units={}", documents.size(), result.units().size());
                return result;
            }
            resumed = true;
            log.warn("기존 Gemini 완료 캐시가 현재 완전성 검증에 실패해 누락 보정을 재개합니다: documents={}, warnings={}",
                    documents.size(), result.warnings());
        }

        var progress = resumed ? java.util.Optional.<String>empty() : cache.readProgress(cacheKey);
        progress.ifPresent(value -> accumulator.accept(JSON.readTree(value)));
        resumed = resumed || progress.isPresent();
        if (progress.isPresent() && accumulator.serverVerifiedComplete()) {
            cache.write(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
            cache.deleteProgress(cacheKey);
            GeminiPdfExtractionResult result = toResult(
                    accumulator, inspected, 0, true, GeminiTokenUsage.empty(), "PROGRESS_CACHE"
            );
            log.info("Gemini PDF 부분 캐시가 새 완전성 규칙을 충족해 완료 처리: documents={}, units={}",
                    documents.size(), result.units().size());
            return result;
        }
        GeminiTokenUsage usage = GeminiTokenUsage.empty();
        int calls = 0;
        String finishReason = "";

        String cachedContinuation = resumed
                ? accumulator.continuationDirective(MAX_MISSING_INDEXES_PER_REPAIR) : "";
        boolean needsInitialPass = !resumed
                || (!accumulator.serverVerifiedComplete() && cachedContinuation.isBlank());
        if (needsInitialPass) {
            List<PageSlice> slices = pageSlices(inspected, accumulator, false);
            for (int index = 0; index < slices.size(); index++) {
                PageSlice slice = slices.get(index);
                boolean lastSlice = index == slices.size() - 1;
                String chunkDirective = slices.size() <= 1 ? "" : "페이지 분할 분석 " + (index + 1) + "/"
                        + slices.size() + "입니다. 전달된 원본 페이지만 추출하고, 원문 연번을 문서 전체 기준 "
                        + "extractionIndex로 사용하세요. " + (lastSlice
                        ? "마지막 묶음이므로 전달된 페이지를 모두 확인했다면 complete=true로 반환하세요."
                        : "아직 다른 페이지 묶음이 남아 있으므로 complete=false로 반환하세요.");
                ParsedApiResponse parsed = executeSlice(
                        slice, "", chunkDirective, accumulator, index > 0);
                accumulator.accept(parsed.payload());
                usage = usage.plus(parsed.usage());
                finishReason = parsed.finishReason();
                calls++;
                cache.writeProgress(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
            }
        }

        int repairRounds = 0;
        String continuationDirective = accumulator.continuationDirective(MAX_MISSING_INDEXES_PER_REPAIR);
        while (!accumulator.serverVerifiedComplete()
                && !continuationDirective.isBlank()
                && repairRounds < properties.maxContinuationCalls()) {
            List<PageSlice> repairs = pageSlices(inspected, accumulator, true);
            for (PageSlice repair : repairs) {
                ParsedApiResponse parsed = executeSlice(
                        repair, continuationDirective, "", accumulator, true);
                accumulator.accept(parsed.payload());
                usage = usage.plus(parsed.usage());
                finishReason = parsed.finishReason();
                calls++;
                cache.writeProgress(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
            }
            repairRounds++;
            continuationDirective = accumulator.continuationDirective(MAX_MISSING_INDEXES_PER_REPAIR);
        }

        GeminiPdfExtractionResult result = toResult(
                accumulator, inspected, calls, false, usage, finishReason
        );
        if (result.complete()) {
            cache.write(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
            cache.deleteProgress(cacheKey);
        }
        return result;
    }

    private ParsedApiResponse executeSlice(
            PageSlice slice,
            String continuationDirective,
            String chunkDirective,
            PayloadAccumulator accumulator,
            boolean continuation
    ) {
        String response = generateWithRetry(requestBody(
                List.of(slice.document()), Map.of(slice.document().document().documentId(), slice.pages()),
                continuationDirective, accumulator.units.size(), accumulator, chunkDirective
        ), continuation);
        ParsedApiResponse parsed = parseApiResponse(response);
        log.info("Gemini PDF 호출 완료: documentId={}, pages={}, continuation={}, promptTokens={}, "
                        + "candidateTokens={}, thoughtTokens={}, totalTokens={}, finishReason={}",
                slice.document().document().documentId(), slice.pages(), continuation,
                parsed.usage().promptTokens(), parsed.usage().candidateTokens(),
                parsed.usage().thoughtTokens(), parsed.usage().totalTokens(), parsed.finishReason());
        return parsed;
    }

    private List<PageSlice> pageSlices(
            List<InspectedDocument> documents,
            PayloadAccumulator accumulator,
            boolean repair
    ) {
        List<PageSlice> result = new ArrayList<>();
        for (InspectedDocument document : documents) {
            Set<Integer> pages = repair
                    ? accumulator.repairPages(document.document().documentId(), document)
                    : document.relevantPages();
            for (Set<Integer> slice : splitPagesToLimit(document, pages)) {
                result.add(new PageSlice(document, slice));
            }
        }
        return List.copyOf(result);
    }

    private List<Set<Integer>> splitPagesToLimit(InspectedDocument document, Set<Integer> pages) {
        List<Integer> ordered = new ArrayList<>(new TreeSet<>(pages));
        List<Set<Integer>> result = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += properties.maxPagesPerRequest()) {
            int end = Math.min(ordered.size(), start + properties.maxPagesPerRequest());
            splitPagesToLimit(document, ordered.subList(start, end), result);
        }
        return List.copyOf(result);
    }

    private void splitPagesToLimit(
            InspectedDocument document,
            List<Integer> pages,
            List<Set<Integer>> target
    ) {
        Set<Integer> pageSet = Set.copyOf(pages);
        byte[] subset = subsetPdf(document.document().rawBytes(), pageSet, document.document().fileName());
        if (subset.length <= properties.maxFileBytes()) {
            target.add(pageSet);
            return;
        }
        if (pages.size() == 1) {
            throw new IllegalArgumentException("단일 페이지 PDF 크기 제한 초과: "
                    + document.document().fileName() + ", page=" + pages.getFirst());
        }
        int middle = pages.size() / 2;
        splitPagesToLimit(document, pages.subList(0, middle), target);
        splitPagesToLimit(document, pages.subList(middle, pages.size()), target);
    }

    /** 429·5xx 일시 장애는 호출 예산 안에서 짧게 재시도하고, 계속 실패하면 부분 캐시를 다음 실행에 남긴다. */
    private String generateWithRetry(String requestBody, boolean continuation) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            budget.acquire(continuation || attempt > 0);
            try {
                return transport.generate(requestBody);
            } catch (RuntimeException exception) {
                last = exception;
                if (!isTransientApiFailure(exception) || attempt == MAX_TRANSIENT_RETRIES) throw exception;
                long delayMillis = 1_000L << attempt;
                log.warn("Gemini 일시 장애로 재시도합니다: attempt={}/{}, delayMs={}, reason={}",
                        attempt + 1, MAX_TRANSIENT_RETRIES, delayMillis, exception.getMessage());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Gemini 재시도 대기가 중단되었습니다.", interrupted);
                }
            }
        }
        throw last;
    }

    private boolean isTransientApiFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) return false;
        return message.contains("status=429") || message.contains("status=500")
                || message.contains("status=502") || message.contains("status=503")
                || message.contains("status=504") || message.contains("분석 요청에 실패했습니다");
    }

    private void validateConfiguration() {
        if (!properties.enabled()) throw new IllegalStateException("Gemini PDF 직접 추출이 비활성화되어 있습니다.");
        if (properties.key() == null || properties.key().isBlank()) {
            throw new IllegalStateException("Gemini API 키가 설정되지 않았습니다.");
        }
    }

    private List<InspectedDocument> inspect(List<GeminiPdfDocument> documents) {
        if (documents == null || documents.isEmpty()) throw new IllegalArgumentException("분석할 PDF가 없습니다.");
        Set<String> ids = new HashSet<>();
        List<InspectedDocument> result = new ArrayList<>();
        int totalPages = 0;
        for (GeminiPdfDocument document : documents) {
            if (!ids.add(document.documentId())) throw new IllegalArgumentException("중복 documentId: " + document.documentId());
            byte[] bytes = document.rawBytes();
            PdfInspection pdf = inspectPdf(bytes, document.fileName());
            totalPages += pdf.pages();
            Integer expected = maximumExpectedCount(
                    document.expectedUnitCountHint(), pdf.estimatedUnitCount());
            result.add(new InspectedDocument(
                    document, sha256(bytes), pdf.pages(), expected, pdf.pageTexts(), pdf.relevantPages(),
                    detectRequiredAttributes(pdf.pageTexts()), detectTableColumns(pdf.pageTexts())));
        }
        if (totalPages > 1_000) throw new IllegalArgumentException("한 요청의 PDF 페이지 합계가 1000페이지를 초과했습니다.");
        return List.copyOf(result);
    }

    private Integer maximumExpectedCount(Integer hint, Integer inspected) {
        if (hint == null) return inspected;
        if (inspected == null) return hint;
        return Math.max(hint, inspected);
    }

    private PdfInspection inspectPdf(byte[] bytes, String fileName) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            Map<Integer, String> pageTexts = new LinkedHashMap<>();
            StringBuilder fullText = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                pageTexts.put(page, text);
                fullText.append(text).append('\n');
            }
            return new PdfInspection(
                    document.getNumberOfPages(), estimateUnitCount(fullText.toString()), pageTexts,
                    selectRelevantPages(pageTexts));
        } catch (IOException exception) {
            throw new IllegalArgumentException("올바른 PDF가 아닙니다: " + fileName, exception);
        }
    }

    /** 표의 값을 해석하지 않고 1..N 연번이 빠짐없이 있는지만 확인해 배열 길이 검증에 사용한다. */
    Integer estimateUnitCount(String text) {
        if (text == null || text.isBlank()) return null;
        if (!hasSupplyTableHeader(text)) return null;

        Set<Integer> numbers = new HashSet<>();
        Matcher matcher = LEADING_ROW_NUMBER.matcher(text);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value <= 2_000) numbers.add(value);
        }
        if (!numbers.contains(1)) return null;
        int consecutive = 1;
        while (numbers.contains(consecutive + 1)) consecutive++;
        return consecutive >= 2 ? consecutive : null;
    }

    private boolean hasSupplyTableHeader(String text) {
        String[] lines = text.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].contains("연번") && !lines[index].contains("순번")) continue;
            StringBuilder window = new StringBuilder(lines[index]);
            for (int next = index + 1; next < lines.length && next <= index + 2; next++) {
                window.append(' ').append(lines[next]);
            }
            long markerCount = SUPPLY_TABLE_MARKERS.stream().filter(window.toString()::contains).count();
            if (markerCount >= 2) return true;
        }
        return false;
    }

    private Set<Integer> selectRelevantPages(Map<Integer, String> pageTexts) {
        Set<Integer> strongPages = new TreeSet<>();
        for (Map.Entry<Integer, String> entry : pageTexts.entrySet()) {
            String text = entry.getValue() == null ? "" : entry.getValue();
            long markers = SUPPLY_TABLE_MARKERS.stream().filter(text::contains).count();
            boolean tableHeader = (text.contains("연번") || text.contains("순번")) && markers >= 2;
            int rowLines = 0;
            Matcher matcher = LEADING_ROW_NUMBER.matcher(text);
            while (matcher.find() && rowLines < 3) rowLines++;
            if (tableHeader || rowLines >= 2) strongPages.add(entry.getKey());
        }
        if (strongPages.isEmpty()) return Set.copyOf(pageTexts.keySet());

        Set<Integer> selected = new TreeSet<>();
        int pageCount = pageTexts.size();
        for (int page : strongPages) {
            for (int candidate = Math.max(1, page - 1); candidate <= Math.min(pageCount, page + 1); candidate++) {
                selected.add(candidate);
            }
        }
        return Set.copyOf(selected);
    }

    private Map<String, Set<Integer>> detectRequiredAttributes(Map<Integer, String> pageTexts) {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("주차", List.of("주차대수", "주차 대수", "주차면수", "주차 면수", "주차장"));
        keywords.put("건물 층수", List.of("건물 층수", "건물층수", "지상층수"));
        keywords.put("건물 전체 세대수", List.of("전체 세대수", "전체세대수", "총 세대수", "총세대수"));
        keywords.put("주민공동시설 종류", List.of("주민공동시설", "공동시설 종류", "커뮤니티시설"));
        keywords.put("세대 구조", List.of("세대 구조", "세대구조", "주택 구조", "주택구조"));
        keywords.put("기본 옵션", List.of("기본 옵션", "기본옵션", "빌트인", "구비품목"));
        keywords.put("베란다", List.of("베란다", "발코니"));
        keywords.put("옥상 출입", List.of("옥상 출입", "옥상출입"));
        keywords.put("기계식 주차", List.of("기계식 주차", "기계식주차"));
        keywords.put("난방 방식", List.of("난방 방식", "난방방식"));

        Map<String, Set<Integer>> found = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> page : pageTexts.entrySet()) {
            String compact = page.getValue() == null ? "" : page.getValue().replaceAll("\\s+", "");
            for (Map.Entry<String, List<String>> requirement : keywords.entrySet()) {
                boolean exists = requirement.getValue().stream()
                        .map(value -> value.replaceAll("\\s+", ""))
                        .anyMatch(compact::contains);
                if (exists) found.computeIfAbsent(requirement.getKey(), ignored -> new TreeSet<>())
                        .add(page.getKey());
            }
            if (compact.matches(".*지상\\d+층.*")) {
                found.computeIfAbsent("건물 층수", ignored -> new TreeSet<>()).add(page.getKey());
            }
            if (compact.contains("공급시설") && compact.matches(".*\\d+세대.*")) {
                found.computeIfAbsent("건물 전체 세대수", ignored -> new TreeSet<>()).add(page.getKey());
            }
            if (compact.matches(".*주민공동시설\\([^)]{1,80}\\).*")) {
                found.computeIfAbsent("주민공동시설 종류", ignored -> new TreeSet<>()).add(page.getKey());
            }
        }
        return Map.copyOf(found);
    }

    private Set<String> detectTableColumns(Map<Integer, String> pageTexts) {
        String text = String.join("\n", pageTexts.values()).replaceAll("\\s+", "");
        Map<String, List<String>> markers = new LinkedHashMap<>();
        markers.put("complexName", List.of("주택명", "단지명"));
        markers.put("address", List.of("도로명주소", "소재지", "주소"));
        markers.put("buildingDong", List.of("동호", "동명"));
        markers.put("unitNumber", List.of("호수", "동호"));
        markers.put("floor", List.of("해당층", "층수"));
        markers.put("exclusiveArea", List.of("전용면적", "주거전용"));
        markers.put("commonArea", List.of("공용면적", "주거공용"));
        markers.put("rentalArea", List.of("임대면적"));
        markers.put("roomCount", List.of("방수"));
        markers.put("housingType", List.of("주택유형"));
        markers.put("supplyType", List.of("공급유형", "공급형"));
        markers.put("rentDeposit", List.of("임대보증금", "보증금"));
        markers.put("monthlyRent", List.of("월임대료", "월세"));
        markers.put("supplyStatus", List.of("공급상태"));
        markers.put("genderEligibility", List.of("성별용도", "성별"));
        markers.put("housingViewingSchedule", List.of("주택열람일정", "열람일정"));
        markers.put("nearbySubwayStation", List.of("인근역", "지하철역"));
        markers.put("subwayWalkDistanceKm", List.of("도보거리", "역거리"));
        markers.put("elevatorAvailable", List.of("승강기유무", "승강기"));
        markers.put("communityFacilityAvailable", List.of("공동시설유무"));
        Set<String> result = new HashSet<>();
        markers.forEach((field, aliases) -> {
            if (aliases.stream().anyMatch(text::contains)) result.add(field);
        });
        boolean multipleRentalConditions = (text.contains("1순위") && text.contains("2순위"))
                || (text.contains("50%이하") && text.contains("50%초과"));
        if (multipleRentalConditions) result.add("multipleRentalConditions");
        return Set.copyOf(result);
    }

    private String requestBody(
            List<InspectedDocument> documents,
            Map<String, Set<Integer>> pageOverrides,
            String continuationDirective,
            int storedUnitCount,
            PayloadAccumulator accumulator,
            String chunkDirective
    ) {
        List<Map<String, Object>> parts = new ArrayList<>();
        long requestBytes = 0;
        for (InspectedDocument inspected : documents) {
            GeminiPdfDocument document = inspected.document();
            Set<Integer> requestedPages = pageOverrides.getOrDefault(
                    document.documentId(), inspected.relevantPages());
            if (requestedPages.isEmpty()) requestedPages = inspected.relevantPages();
            byte[] requestPdf = subsetPdf(document.rawBytes(), requestedPages, document.fileName());
            requestBytes += requestPdf.length;
            if (requestBytes > properties.maxFileBytes()) {
                throw new IllegalArgumentException("선별 페이지 PDF 크기 제한 초과: " + document.fileName());
            }
            parts.add(Map.of("text", "문서 ID=" + document.documentId()
                    + ", 기관=" + safe(document.agency())
                    + ", 공고명=" + safe(document.noticeTitle())
                    + ", 파일명=" + safe(document.fileName())
                    + ", 전체페이지=" + inspected.pages()
                    + ", 전달페이지매핑=" + pageMapping(requestedPages)
                    + (inspected.requiredAttributes().isEmpty() ? ""
                    : ", 필수추가속성후보=" + inspected.requiredAttributes().keySet())
                    + (inspected.expectedUnitCount() == null
                    ? ", 공급행수=서버 사전확인 불가"
                    : ", 공급행수=" + inspected.expectedUnitCount() + "(서버 사전확인)")));
            parts.add(Map.of("inlineData", Map.of(
                    "mimeType", "application/pdf",
                    "data", Base64.getEncoder().encodeToString(requestPdf)
            )));
        }
        parts.add(Map.of("text", prompt(continuationDirective, storedUnitCount, chunkDirective)));

        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("responseMimeType", "application/json");
        generation.put("responseJsonSchema", responseSchema());
        generation.put("maxOutputTokens", properties.maxOutputTokens());
        generation.put("temperature", 0.0);
        generation.put("thinkingConfig", Map.of("thinkingLevel", properties.thinkingLevel()));
        return JSON.writeValueAsString(Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", generation
        ));
    }

    private byte[] subsetPdf(byte[] sourceBytes, Set<Integer> originalPages, String fileName) {
        try (PDDocument source = Loader.loadPDF(sourceBytes);
             PDDocument target = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page : new TreeSet<>(originalPages)) {
                if (page >= 1 && page <= source.getNumberOfPages()) target.importPage(source.getPage(page - 1));
            }
            if (target.getNumberOfPages() == 0) return sourceBytes;
            target.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Gemini 전달용 PDF 페이지 생성 실패: " + fileName, exception);
        }
    }

    private String pageMapping(Set<Integer> pages) {
        int requestPage = 1;
        List<String> mapping = new ArrayList<>();
        for (int originalPage : new TreeSet<>(pages)) {
            mapping.add(requestPage++ + "->" + originalPage);
        }
        return String.join(",", mapping);
    }

    private String prompt(String continuationDirective, int storedUnitCount, String chunkDirective) {
        String continuation = continuationDirective == null || continuationDirective.isBlank()
                ? "첫 분석입니다. 이번 응답에 모든 공급 행을 반환하세요."
                : "후속 보정 분석입니다. 서버가 이전 응답의 extractionIndex를 검증했고 "
                + storedUnitCount + "건을 저장했습니다. 아래에 지정한 누락 extractionIndex만 원문에서 다시 찾아 "
                + "하나도 빼지 말고 모두 반환하세요. 지정되지 않은 행은 반환하지 마세요.\n"
                + continuationDirective;
        String chunk = chunkDirective == null || chunkDirective.isBlank() ? "" : "\n" + chunkDirective;
        return """
                당신은 한국 공공임대 공고 PDF 전문 추출기입니다. %s%s

                [완료 계약]
                - 문서 메타데이터에 공급행수가 숫자로 주어지면 units 배열은 반드시 정확히 그 개수여야 합니다.
                - 공급행수는 서버가 PDF 표의 1..N 연번을 사전 검증한 값이므로 다시 줄이거나 대표 행만 선택하지 마세요.
                - 예시 행, 요약 행, 일부 행만 반환하는 것은 실패입니다. 첫 행부터 마지막 행까지 모두 반환하세요.
                - 응답을 끝내기 전에 expectedUnitCount와 units.length가 같은지 검산하세요.
                - 각 unit의 sourceTableId에는 같은 표의 행끼리 같은 값(documentId/table-1 형식)을 기록하세요.
                - 같은 공급표에서 한 행이라도 반환한 열은 모든 행을 확인하세요. 일부 행만 값이 빠진 상태로
                  complete=true를 반환하지 마세요.
                - 원문 셀에 '-', '해당 없음', '공고문 확인', '별도 문의'가 명시돼 null인 경우에만 해당 필드명을
                  verifiedNullFields에 넣으세요. 값을 찾지 못했다는 이유로 verifiedNullFields에 넣으면 안 됩니다.
                  단순 공란은 서버가 원문 근거를 확인할 수 없으므로 verifiedNullFields에 넣지 마세요.
                  실제 값이 있는 셀을 null로 두거나 검증된 NULL로 표시하지 마세요.
                - 메타데이터에 필수추가속성후보가 있으면 실제 주택·건물 정보인지 확인해 고정 필드와
                  additionalAttributes에 빠짐없이 기록하세요. 주택 정보가 아니라면 warnings에 제외 근거를 기록하세요.

                정규식이나 고정 열 순서를 가정하지 말고 전달된 PDF 페이지를 직접 읽으세요. 텍스트, 이미지, 표가 섞여 있어도
                공급주택 목록과 임대조건, 다음 페이지로 이어지는 표와 주석·단위를 함께 해석하세요.
                서버가 PDFBox로 공급표 후보 페이지와 앞뒤 연결 페이지를 선별했습니다. 메타데이터의 전달페이지매핑은
                '전달된 PDF 페이지 -> 원본 PDF 페이지'이므로 모든 page와 evidencePage에는 원본 페이지 번호를 기록하세요.
                모집 절차, 자격, 신청서 양식처럼 주택 상세정보가 아닌 페이지는 추출 대상에서 제외하세요.

                [평면 응답 규칙]
                - documents, buildings, units, rentalConditions, additionalAttributes는 모두 최상위 배열입니다.
                - units 내부에 rentalConditions나 additionalAttributes 배열을 넣지 마세요.
                - documents와 buildings 내부에도 additionalAttributes 배열을 넣지 마세요.
                - rentalConditions는 unitId로 units와 연결하세요.
                - additionalAttributes는 scope에 맞춰 documentId, buildingId, unitId 중 대상 ID를 채우고 나머지 ID는 빈 문자열로 두세요.

                건물 공통값은 buildings에 한 번만 기록하고 units가 buildingId로 참조하게 하세요.
                단, 누락 보정 응답만으로도 각 행을 완전하게 저장할 수 있도록 모든 unit에 complexName, provinceName,
                districtName, address, lotAddress, roadAddress, housingType도 해당 행 기준으로 반복해서 기록하세요.
                승강기 여부처럼 같은 건물에서도 공급 행마다 값이 달라질 수 있는 항목은 unit에 기록하세요.
                unit 값이 건물 공통값보다 우선하며, 원문에 명시되지 않은 불리언 값은 false로 추론하지 말고 생략하세요.
                동·호 정보가 없는 주소별 또는 면적형별 공급도 하나의 unit으로 기록하고 supplyCount를 채우세요.
                금액은 원 단위 정수, 면적은 제곱미터, 거리는 km로 정규화하세요. 추론하지 말고 원문에 없는 값은 생략하세요.
                표에 공용면적과 임대면적 열이 있으면 각 unit의 commonArea와 rentalArea에 숫자로 반드시 기록하세요.
                주소는 원문의 시·군·구·읍·면·동 계층을 하나도 생략하지 말고 address와 roadAddress에 보존하세요.
                보증금과 월 임대료는 원문의 콤마 구분 자릿수와 대조해 10배·10분의 1 오류가 없는지 행별로 다시 확인하세요.
                베란다, 발코니, 옥상 출입, 주차 방식·주차 대수, 창고, 반지하 여부, 채광, 방향, 냉난방,
                가전·가구·보안·공동시설·기본 옵션처럼 고정 필드에 없지만 원문에 있는 정보는
                누락하지 말고 최상위 additionalAttributes 배열에 각각 보존하세요.
                건물 층수, 전체 세대수, 공동시설 종류, 기본 옵션처럼 독립적으로 조회할 사실은 하나의 포괄적인 속성으로
                합치지 말고 각각 별도의 additionalAttributes 항목으로 기록하며 구체적인 종류와 값을 생략하지 마세요.
                표에 별도 열로 존재하는 정보는 DB 고정 필드가 아니더라도 열 이름을 name, 해당 행의 값을 value로 기록하세요.
                특히 원문에 있으면 '건물 층수', '건물 전체 세대수', '주민공동시설 종류', '세대 구조', '기본 옵션'을
                각각 독립된 additionalAttributes 항목으로 반드시 기록하세요. 여러 옵션을 임의로 줄이거나 대표 항목만 고르지 마세요.
                주택 공급표에 성별용도와 주택열람일정 열이 있으면 unit의 genderEligibility와 housingViewingSchedule에
                각각 기록하세요. supplyStatus에는 모집·공급 상태만 기록하고 열람일정을 넣지 마세요.
                주택 개별 목록이 없는 공고문은 units를 만들거나 주소를 추측하지 마세요. 대신 원문에 명시된 공급대상 주택 수,
                기본 임대기간, 재계약 가능 횟수, 최장 거주기간, 임대조건, 신청 시작·마감, 신청 방식을 DOCUMENT 범위의
                additionalAttributes로 기록하세요.
                주차면수·주차대수가 숫자로 명시되면 additionalAttributes뿐 아니라 buildings.totalParkingSpaces에도 숫자로 기록하세요.
                additionalAttributes의 scope는 DOCUMENT, BUILDING, UNIT 중 하나를 사용하고 page와 연속 원문 evidence를 반드시 넣으세요.
                임대조건이 여러 개일 때만 모두 최상위 rentalConditions에 기록하세요. 조건이 하나뿐이면 rentalConditions는 비우고
                unit의 rentDeposit과 monthlyRent만 기록해 중복 출력을 피하세요. 원문 표에서 첫 번째 또는 기본으로 제시된 조건의
                보증금과 월 임대료를 unit의 rentDeposit, monthlyRent에도 동일하게 기록하세요.
                각 unit에는 원문 page와 PDF에 실제로 연속해 적힌 30자 이내의 문구를 evidence로 그대로 인용하세요.
                page는 표지 포함 PDF의 실제 페이지 번호이며 1 이상 문서 전체페이지 이하만 허용됩니다.
                '몇 페이지 연번 몇 번'과 같은 설명이나 요약은 evidence가 아닙니다. 주소·호수·면적·금액 중 원문에서
                실제로 확인한 값을 포함한 표 행 문구를 인용하세요.

                출력량을 임의로 예상해 미리 끊지 말고 이번 응답에 전 건을 출력하는 것이 원칙입니다.
                각 문서의 첫 공급 행부터 1, 2, 3 순서로 연속된 extractionIndex를 부여하고, 원문 연번은 sourceRowLabel에 별도로 적으세요.
                unitId는 documentId + '/item-' + extractionIndex로 만들고, 반드시 원문 순서대로 한 행도 건너뛰지 마세요.
                정말 한 응답에 완료할 수 없는 경우에만 complete=false로 반환하며, nextCursor에는 추출 대상으로 살펴본 마지막 행이 아니라
                이번 응답에 실제로 반환한 마지막 documentId/unitId를 적으세요. 모두 반환했으면 complete=true와 빈 nextCursor를 반환하세요.
                같은 행을 반복하지 말고 문서별 예상 공급 행 수(expectedUnitCount)도 기록하세요. 반환한 units 수가 예상 수와 같은지 스스로 확인하세요.
                """.formatted(continuation, chunk);
    }

    private ParsedApiResponse parseApiResponse(String responseBody) {
        JsonNode envelope = JSON.readTree(responseBody);
        JsonNode candidates = envelope.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) throw new IllegalStateException("Gemini PDF 분석 응답이 없습니다.");
        JsonNode candidate = candidates.get(0);
        StringBuilder text = new StringBuilder();
        candidate.path("content").path("parts").forEach(part -> text.append(part.path("text").asText()));
        if (text.isEmpty()) throw new IllegalStateException("Gemini PDF 분석 JSON이 비어 있습니다.");
        JsonNode usage = envelope.path("usageMetadata");
        String finishReason = candidate.path("finishReason").asText("");
        JsonNode payload;
        try {
            payload = JSON.readTree(text.toString());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Gemini PDF 분석 JSON이 잘렸거나 올바르지 않습니다. finishReason=" + finishReason,
                    exception
            );
        }
        return new ParsedApiResponse(
                payload,
                new GeminiTokenUsage(
                        usage.path("promptTokenCount").asLong(0),
                        usage.path("candidatesTokenCount").asLong(0),
                        usage.path("thoughtsTokenCount").asLong(0),
                        usage.path("totalTokenCount").asLong(0)
                ),
                finishReason
        );
    }

    private GeminiPdfExtractionResult toResult(
            PayloadAccumulator accumulator,
            List<InspectedDocument> documents,
            int calls,
            boolean fromCache,
            GeminiTokenUsage usage,
            String finishReason
    ) {
        Map<String, JsonNode> buildings = accumulator.buildings;
        List<DetailedHousingUnit> units = new ArrayList<>();
        List<String> warnings = new ArrayList<>(accumulator.warnings);
        Set<String> unitKeys = new HashSet<>();
        Map<String, Set<Integer>> indexesByDocument = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode node : accumulator.units.values()) {
            JsonNode building = buildings.get(node.path("buildingId").asText());
            String documentId;
            if (building == null) {
                // 보정 응답은 unit 자체에 건물 공통 필드를 반복하므로 building 배열이 빠져도 복구할 수 있습니다.
                building = node;
                documentId = documents.size() == 1
                        ? documents.getFirst().document().documentId()
                        : node.path("documentId").asText("");
            } else {
                documentId = building.path("documentId").asText();
            }
            JsonNode documentMetadata = accumulator.documents.get(documentId);
            DetailedHousingUnit unit = toUnit(
                    node, building, documentId, documentMetadata,
                    accumulator.attributesFor("DOCUMENT", documentId),
                    accumulator.attributesFor("BUILDING", building.path("buildingId").asText()),
                    accumulator.attributesFor("UNIT", node.path("unitId").asText()),
                    accumulator.conditionsFor(node.path("unitId").asText()),
                    accumulator.acceptedVerifiedNullFields(node),
                    documents, ++index
            );
            if (!unitKeys.add(unit.sourceUnitKey())) {
                warnings.add("중복 주택 키: " + unit.sourceUnitKey());
                continue;
            }
            if (node.path("page").asInt(0) <= 0 || node.path("evidence").asText().isBlank()) {
                warnings.add("원문 근거 누락: " + unit.sourceUnitKey());
            }
            units.add(unit);
            indexesByDocument.computeIfAbsent(documentId, ignored -> new HashSet<>())
                    .add(node.path("extractionIndex").asInt(0));
        }
        int modelExpected = accumulator.documents.values().stream()
                .mapToInt(value -> value.path("expectedUnitCount").asInt(0)).sum();
        Integer trustedExpected = documents.stream().allMatch(value -> value.expectedUnitCount() != null)
                ? documents.stream().mapToInt(InspectedDocument::expectedUnitCount).sum()
                : null;
        // 기존 파서/PDFBox가 확인한 수는 최소 보장치다. 복잡한 표에서는 한 행만 읽고도
        // 힌트가 1이 될 수 있으므로, AI가 더 많은 연속 행을 확인했다면 큰 값을 기준으로 검증한다.
        int expected = trustedExpected == null ? modelExpected : Math.max(trustedExpected, modelExpected);
        if (trustedExpected != null && modelExpected != trustedExpected) {
            warnings.add("모델 예상 행 수 불일치: server=" + trustedExpected + ", model=" + modelExpected);
        }
        boolean countMatches = trustedExpected == null
                ? expected == 0 || expected == units.size()
                : expected == units.size();
        if (!countMatches) warnings.add("예상 행 수 불일치: expected=" + expected + ", extracted=" + units.size());
        boolean sequenceComplete = validateSequences(accumulator.documents, documents, indexesByDocument, warnings);
        Map<String, List<String>> inconsistentFields = accumulator.inconsistentFieldRows();
        if (!inconsistentFields.isEmpty()) {
            warnings.add("같은 공급표의 일부 행에서만 열 누락: " + inconsistentFields);
        }
        boolean tokenCut = "MAX_TOKENS".equalsIgnoreCase(finishReason);
        if (tokenCut) warnings.add("출력 토큰 한도에 도달했습니다.");
        boolean complete = accumulator.serverVerifiedComplete() && countMatches && sequenceComplete && !tokenCut;
        if (!complete && calls >= 1 + properties.maxContinuationCalls()) {
            warnings.add("허용된 후속 호출 횟수 안에 추출을 완료하지 못했습니다.");
        }
        return new GeminiPdfExtractionResult(
                units, complete, accumulator.nextCursor, expected, calls, fromCache, usage,
                accumulator.relevantPages(), accumulator.allAttributes(), warnings
        );
    }

    private DetailedHousingUnit toUnit(
            JsonNode unit,
            JsonNode building,
            String documentId,
            JsonNode documentMetadata,
            List<JsonNode> documentAttributes,
            List<JsonNode> buildingAttributes,
            List<JsonNode> unitAttributes,
            List<JsonNode> flatConditions,
            Set<String> verifiedNullFields,
            List<InspectedDocument> documents,
            int index
    ) {
        String hash = documents.stream()
                .filter(value -> value.document().documentId().equals(documentId))
                .map(InspectedDocument::sha256).findFirst().orElse(documentId);
        int extractionIndex = unit.path("extractionIndex").asInt(index);
        String unitId = unit.path("unitId").asText("item-" + extractionIndex);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("parser", PROMPT_VERSION);
        raw.put("documentId", documentId);
        raw.put("sourceTableId", unit.path("sourceTableId").asText(""));
        raw.put("buildingId", unit.path("buildingId").asText(""));
        raw.put("verifiedNullFields", verifiedNullFields);
        String modelEvidence = unit.path("evidence").asText("");
        VerifiedEvidence verifiedEvidence = verifyEvidence(
                documentId, modelEvidence, unit.path("page").asInt(0), documents);
        String evidence = verifiedEvidence.text();
        int evidencePage = verifiedEvidence.page();
        raw.put("page", evidencePage);
        raw.put("evidence", evidence);
        raw.put("documentAdditionalAttributes", mergeAttributes(
                documentMetadata == null ? null : documentMetadata.path("additionalAttributes"), documentAttributes));
        raw.put("buildingAdditionalAttributes", mergeAttributes(building.path("additionalAttributes"), buildingAttributes));
        List<Object> normalizedUnitAttributes = mergeAttributes(unit.path("additionalAttributes"), unitAttributes);
        appendAreaAttribute(normalizedUnitAttributes, "공용면적", nullableDecimal(unit, "commonArea"),
                evidencePage, evidence);
        appendAreaAttribute(normalizedUnitAttributes, "임대면적", nullableDecimal(unit, "rentalArea"),
                evidencePage, evidence);
        appendTextAttribute(normalizedUnitAttributes, "성별용도", text(unit, "genderEligibility", null),
                evidencePage, evidence);
        appendTextAttribute(normalizedUnitAttributes, "주택열람일정",
                firstNonBlank(text(unit, "housingViewingSchedule", null),
                        viewingScheduleFromSupplyStatus(text(unit, "supplyStatus", null))),
                evidencePage, evidence);
        raw.put("unitAdditionalAttributes", normalizedUnitAttributes);
        List<DetailedRentalCondition> conditions = new ArrayList<>();
        int conditionIndex = 0;
        List<JsonNode> conditionNodes = new ArrayList<>();
        unit.path("rentalConditions").forEach(conditionNodes::add); // 이전 캐시 응답 호환
        conditionNodes.addAll(flatConditions);
        for (JsonNode condition : conditionNodes) {
            String conditionName = text(condition, "conditionName", "조건별 임대료");
            Long conditionDeposit = nullableLong(condition, "rentDeposit");
            Long conditionMonthlyRent = nullableLong(condition, "monthlyRent");
            conditions.add(new DetailedRentalCondition(
                    text(condition, "conditionCode", "C" + ++conditionIndex),
                    conditionName,
                    conditionDeposit,
                    conditionMonthlyRent,
                    JSON.writeValueAsString(Map.of(
                            "page", resolveEvidencePage(documentId, condition.path("evidence").asText(""),
                                    condition.path("page").asInt(evidencePage), documents),
                            "evidence", condition.path("evidence").asText("")
                    ))
            ));
            String attributePrefix = normalizeConditionName(conditionName);
            appendLongAttribute(normalizedUnitAttributes, attributePrefix + " 임대보증금",
                    conditionDeposit, evidencePage, evidence);
            appendLongAttribute(normalizedUnitAttributes, attributePrefix + " 월임대료",
                    conditionMonthlyRent, evidencePage, evidence);
        }
        String complexName = firstNonBlank(text(unit, "complexName", null), text(building, "complexName", null));
        String provinceName = firstNonBlank(text(unit, "provinceName", null), text(building, "provinceName", null));
        String districtName = firstNonBlank(text(unit, "districtName", null), text(building, "districtName", null));
        String lotAddress = longestNonBlank(text(unit, "lotAddress", null), text(building, "lotAddress", null));
        String roadAddress = longestNonBlank(text(unit, "roadAddress", null), text(building, "roadAddress", null));
        String generalAddress = longestNonBlank(text(unit, "address", null), text(building, "address", null));
        roadAddress = completeRoadAddress(districtName, roadAddress, generalAddress, evidence);
        roadAddress = sanitizeRoadAddress(roadAddress, complexName);
        generalAddress = sanitizeRoadAddress(generalAddress, complexName);
        // 일반 주소 뒤에 지번 주소가 함께 붙은 경우에도 좌표 변환에는 완전한 도로명주소만 사용합니다.
        String address = firstNonBlank(roadAddress, generalAddress, lotAddress);
        return new DetailedHousingUnit(
                "GEMINI:" + hash.substring(0, Math.min(16, hash.length())) + ":" + unitId,
                complexName,
                provinceName, districtName, address,
                text(unit, "buildingDong", null), text(unit, "unitNumber", null), nullableInt(unit, "floor"),
                nullableDecimal(unit, "exclusiveArea"), nullableInt(unit, "roomCount"),
                firstNonBlank(text(unit, "housingType", null), text(building, "housingType", null),
                        text(unit, "supplyType", null)),
                text(unit, "supplyType", null),
                firstNonNull(nullableLong(unit, "rentDeposit"), firstConditionDeposit(conditions)),
                firstNonNull(nullableLong(unit, "monthlyRent"), firstConditionMonthlyRent(conditions)),
                JSON.writeValueAsString(raw),
                lotAddress, roadAddress,
                extractionIndex, text(unit, "supplyStatus", null),
                firstNonBlank(text(unit, "nearbySubwayStation", null), text(building, "nearbySubwayStation", null)),
                firstNonNull(nullableDecimal(unit, "subwayWalkDistanceKm"),
                        nullableDecimal(building, "subwayWalkDistanceKm")),
                firstNonNull(nullableBoolean(unit, "elevatorAvailable"), nullableBoolean(building, "elevatorAvailable")),
                firstNonNull(nullableBoolean(unit, "communityFacilityAvailable"),
                        nullableBoolean(building, "communityFacilityAvailable")),
                firstNonNull(nullableInt(building, "totalParkingSpaces"),
                        integerFromAttributes(buildingAttributes, "주차")),
                nullableBoolean(building, "mechanicalParkingAvailable"),
                nullableInt(building, "mechanicalParkingSpaces"), nullableLong(unit, "supplyCount"), conditions
        );
    }

    private String completeRoadAddress(
            String districtName, String roadAddress, String generalAddress, String evidence
    ) {
        String evidenceAddress = roadAddressFromEvidence(districtName, evidence);
        if (evidenceAddress != null) return evidenceAddress;
        if (roadAddress == null) return generalAddress;
        String completed = roadAddress;
        for (String candidate : List.of(
                generalAddress == null ? "" : generalAddress,
                evidence == null ? "" : evidence
        )) {
            int roadStart = candidate.indexOf(roadAddress);
            if (roadStart < 0) continue;
            int prefixStart = 0;
            if (districtName != null) {
                int districtStart = candidate.lastIndexOf(districtName, roadStart);
                if (districtStart >= 0) prefixStart = districtStart;
            }
            String expanded = candidate.substring(prefixStart, roadStart + roadAddress.length()).trim();
            if (expanded.length() > completed.length()) completed = expanded;
        }
        return completed;
    }

    String roadAddressFromEvidence(String districtName, String evidence) {
        if (districtName == null || districtName.isBlank() || evidence == null || evidence.isBlank()) return null;
        Matcher matcher = Pattern.compile(
                Pattern.quote(districtName) + "\\s+(.{1,70}(?:로|길)\\s+\\d+(?:-\\d+)?)"
        ).matcher(evidence.replaceAll("\\s+", " "));
        if (!matcher.find()) return null;
        return (districtName + " " + matcher.group(1)).replaceAll("\\s+", " ").trim();
    }

    private String sanitizeRoadAddress(String value, String complexName) {
        if (value == null) return null;
        String sanitized = value.replaceAll("\\s*\\([^()]*\\)", " ")
                .replaceAll("\\s+", " ").trim();
        if (complexName != null && !complexName.isBlank()) {
            sanitized = sanitized.replaceFirst("\\s+" + Pattern.quote(complexName) + "\\s*$", "").trim();
        }
        return sanitized;
    }

    private List<Object> mergeAttributes(JsonNode legacyNested, List<JsonNode> flattened) {
        List<Object> result = new ArrayList<>();
        if (legacyNested != null && legacyNested.isArray()) {
            legacyNested.forEach(value -> result.add(JSON.convertValue(value, Object.class)));
        }
        flattened.forEach(value -> result.add(JSON.convertValue(value, Object.class)));
        return result;
    }

    private void appendAreaAttribute(
            List<Object> attributes, String name, BigDecimal value, int page, String evidence
    ) {
        if (value == null) return;
        attributes.add(Map.of(
                "name", name, "value", value.stripTrailingZeros().toPlainString(),
                "scope", "UNIT", "page", page, "evidence", evidence
        ));
    }

    private void appendTextAttribute(
            List<Object> attributes, String name, String value, int page, String evidence
    ) {
        if (value == null || value.isBlank()) return;
        attributes.add(Map.of(
                "name", name, "value", value, "scope", "UNIT",
                "page", page, "evidence", evidence
        ));
    }

    private void appendLongAttribute(
            List<Object> attributes, String name, Long value, int page, String evidence
    ) {
        if (value == null) return;
        attributes.add(Map.of(
                "name", name, "value", Long.toString(value), "scope", "UNIT",
                "page", page, "evidence", evidence
        ));
    }

    private String normalizeConditionName(String value) {
        if (value == null) return "조건별";
        return value.replaceAll("(\\d)순위\\s*[,·/]\\s*(\\d)순위", "$1·$2순위");
    }

    private String viewingScheduleFromSupplyStatus(String value) {
        if (value == null) return null;
        return value.contains("열람") || value.contains("개방") ? value : null;
    }

    private Integer integerFromAttributes(List<JsonNode> attributes, String nameFragment) {
        for (JsonNode attribute : attributes) {
            if (!attribute.path("name").asText("").contains(nameFragment)) continue;
            Matcher matcher = Pattern.compile("(\\d[\\d,]*)").matcher(attribute.path("value").asText(""));
            if (matcher.find()) return Integer.parseInt(matcher.group(1).replace(",", ""));
        }
        return null;
    }

    private VerifiedEvidence verifyEvidence(
            String documentId,
            String evidence,
            int reportedPage,
            List<InspectedDocument> documents
    ) {
        InspectedDocument inspected = documents.stream()
                .filter(value -> value.document().documentId().equals(documentId))
                .findFirst().orElse(null);
        if (inspected == null) return new VerifiedEvidence(reportedPage, evidence);
        String compactEvidence = compactEvidence(evidence);
        if (!compactEvidence.isBlank()) {
            String reportedText = inspected.pageTexts().get(reportedPage);
            if (reportedText != null && compactEvidence(reportedText).contains(compactEvidence)) {
                return new VerifiedEvidence(reportedPage, evidence);
            }
            for (Map.Entry<Integer, String> page : inspected.pageTexts().entrySet()) {
                if (compactEvidence(page.getValue()).contains(compactEvidence)) {
                    return new VerifiedEvidence(page.getKey(), evidence);
                }
            }
            String[] tokens = evidence.trim().split("\\s+");
            for (int window = Math.min(8, tokens.length); window >= 2; window--) {
                for (int start = 0; start + window <= tokens.length; start++) {
                    String candidate = String.join(" ", java.util.Arrays.copyOfRange(tokens, start, start + window));
                    String compactCandidate = compactEvidence(candidate);
                    if (compactCandidate.length() < 6) continue;
                    for (Map.Entry<Integer, String> page : inspected.pageTexts().entrySet()) {
                        if (compactEvidence(page.getValue()).contains(compactCandidate)) {
                            return new VerifiedEvidence(page.getKey(), candidate);
                        }
                    }
                }
            }
        }
        int safePage = reportedPage >= 1 && reportedPage <= inspected.pages() ? reportedPage : 0;
        return new VerifiedEvidence(safePage, evidence);
    }

    private int resolveEvidencePage(
            String documentId, String evidence, int reportedPage, List<InspectedDocument> documents
    ) {
        return verifyEvidence(documentId, evidence, reportedPage, documents).page();
    }

    private String compactEvidence(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Za-z가-힣]", "");
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> attribute = object(Map.of(
                "name", described(string(), "원문의 열 이름 또는 독립적인 추가 정보 이름"),
                "value", described(string(), "원문에 명시된 값. 추론값을 만들지 않음"),
                "scope", enumString("DOCUMENT", "BUILDING", "UNIT"),
                "documentId", string(), "buildingId", string(), "unitId", string(),
                "page", integer(), "evidence", string()
        ), List.of("name", "value", "scope", "documentId", "buildingId", "unitId", "page", "evidence"));
        Map<String, Object> condition = object(Map.of(
                "unitId", string(), "conditionCode", string(), "conditionName", string(), "rentDeposit", integer(),
                "monthlyRent", integer(), "page", integer(), "evidence", string()
        ), List.of("unitId", "conditionCode", "conditionName", "page", "evidence"));

        Map<String, Object> document = object(Map.of(
                "documentId", string(), "documentType", string(),
                "relevantPages", array(integer()), "expectedUnitCount", integer()
        ), List.of("documentId", "documentType", "relevantPages", "expectedUnitCount"));

        Map<String, Object> buildingProperties = new LinkedHashMap<>();
        for (String field : List.of("buildingId", "documentId", "complexName", "provinceName",
                "districtName", "address", "lotAddress", "roadAddress", "housingType",
                "nearbySubwayStation")) buildingProperties.put(field, string());
        for (String field : List.of("totalParkingSpaces", "mechanicalParkingSpaces")) buildingProperties.put(field, integer());
        buildingProperties.put("subwayWalkDistanceKm", number());
        for (String field : List.of("elevatorAvailable", "communityFacilityAvailable", "mechanicalParkingAvailable")) {
            buildingProperties.put(field, bool());
        }

        Map<String, Object> unitProperties = new LinkedHashMap<>();
        for (String field : List.of("unitId", "buildingId", "sourceTableId", "sourceRowLabel", "complexName", "provinceName",
                "districtName", "address", "lotAddress", "roadAddress", "housingType", "buildingDong",
                "unitNumber", "supplyType", "supplyStatus", "genderEligibility", "housingViewingSchedule",
                "nearbySubwayStation", "evidence")) {
            unitProperties.put(field, List.of("unitId", "buildingId", "sourceRowLabel", "evidence").contains(field)
                    ? string() : nullable(string()));
        }
        for (String field : List.of("extractionIndex", "floor", "roomCount", "rentDeposit", "monthlyRent", "supplyCount", "page")) {
            unitProperties.put(field, List.of("extractionIndex", "page").contains(field)
                    ? integer() : nullable(integer()));
        }
        unitProperties.put("exclusiveArea", nullable(number()));
        unitProperties.put("commonArea", nullable(number()));
        unitProperties.put("rentalArea", nullable(number()));
        unitProperties.put("subwayWalkDistanceKm", nullable(number()));
        unitProperties.put("elevatorAvailable", nullable(bool()));
        unitProperties.put("communityFacilityAvailable", nullable(bool()));
        unitProperties.put("verifiedNullFields", array(enumString(CONSISTENT_UNIT_FIELDS.toArray(String[]::new))));

        Map<String, Object> unitArray = array(object(unitProperties, new ArrayList<>(unitProperties.keySet())));
        unitArray.put("description", "모든 공급 행을 원문 순서대로 반환합니다. 정확한 개수와 순번은 서버에서 검증합니다.");

        return object(Map.of(
                "documents", array(document),
                "buildings", array(object(buildingProperties, List.of("buildingId", "documentId"))),
                "units", unitArray,
                "rentalConditions", array(condition),
                "additionalAttributes", array(attribute),
                "complete", bool(), "nextCursor", string(), "warnings", array(string())
        ), List.of("documents", "buildings", "units", "rentalConditions", "additionalAttributes",
                "complete", "nextCursor", "warnings"));
    }

    private Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", required);
        result.put("additionalProperties", false);
        return result;
    }

    private Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "array");
        result.put("items", items);
        return result;
    }
    private Map<String, Object> string() { return Map.of("type", "string"); }
    private Map<String, Object> integer() { return Map.of("type", "integer"); }
    private Map<String, Object> number() { return Map.of("type", "number"); }
    private Map<String, Object> bool() { return Map.of("type", "boolean"); }

    private Map<String, Object> enumString(String... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "string");
        result.put("enum", List.of(values));
        return result;
    }

    private Map<String, Object> described(Map<String, Object> schema, String description) {
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("description", description);
        return result;
    }

    private Map<String, Object> nullable(Map<String, Object> schema) {
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("nullable", true);
        return result;
    }

    private String cacheKey(List<InspectedDocument> documents) {
        StringBuilder source = new StringBuilder(PROMPT_VERSION).append('|').append(properties.model());
        documents.forEach(value -> source.append('|').append(value.document().documentId())
                .append(':').append(value.sha256())
                .append(':').append(value.expectedUnitCount() == null ? "UNKNOWN" : value.expectedUnitCount()));
        return sha256(source.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }
    private Integer nullableInt(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
    }
    private Long nullableLong(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).asLong() : null;
    }
    private BigDecimal nullableDecimal(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).decimalValue() : null;
    }
    private Boolean nullableBoolean(JsonNode node, String field) {
        return node.has(field) && node.get(field).isBoolean() ? node.get(field).asBoolean() : null;
    }
    private Long firstConditionDeposit(List<DetailedRentalCondition> conditions) {
        return conditions.isEmpty() ? null : conditions.getFirst().rentDeposit();
    }
    private Long firstConditionMonthlyRent(List<DetailedRentalCondition> conditions) {
        return conditions.isEmpty() ? null : conditions.getFirst().monthlyRent();
    }
    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private String longestNonBlank(String... values) {
        String result = null;
        for (String value : values) {
            if (value != null && !value.isBlank() && (result == null || value.length() > result.length())) result = value;
        }
        return result;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }

    private boolean validateSequences(
            Map<String, JsonNode> documentNodes,
            List<InspectedDocument> inspectedDocuments,
            Map<String, Set<Integer>> indexesByDocument,
            List<String> warnings
    ) {
        boolean valid = true;
        Map<String, Integer> trustedCounts = new LinkedHashMap<>();
        for (InspectedDocument inspected : inspectedDocuments) {
            if (inspected.expectedUnitCount() != null) {
                trustedCounts.put(inspected.document().documentId(), inspected.expectedUnitCount());
            }
        }
        for (JsonNode document : documentNodes.values()) {
            String documentId = document.path("documentId").asText();
            int expected = Math.max(
                    trustedCounts.getOrDefault(documentId, 0),
                    document.path("expectedUnitCount").asInt(0));
            if (expected <= 0) continue;
            Set<Integer> indexes = indexesByDocument.getOrDefault(documentId, Set.of());
            List<Integer> missing = new ArrayList<>();
            for (int value = 1; value <= expected; value++) {
                if (!indexes.contains(value)) missing.add(value);
            }
            if (!missing.isEmpty()) {
                valid = false;
                String sample = missing.subList(0, Math.min(10, missing.size())).toString();
                warnings.add("추출 순번 누락: documentId=" + documentId + ", count=" + missing.size()
                        + ", first=" + sample);
            }
        }
        return valid;
    }

    private record PdfInspection(
            int pages,
            Integer estimatedUnitCount,
            Map<Integer, String> pageTexts,
            Set<Integer> relevantPages
    ) { }

    private record InspectedDocument(
            GeminiPdfDocument document,
            String sha256,
            int pages,
            Integer expectedUnitCount,
            Map<Integer, String> pageTexts,
            Set<Integer> relevantPages,
            Map<String, Set<Integer>> requiredAttributes,
            Set<String> detectedTableColumns
    ) { }
    private record ParsedApiResponse(JsonNode payload, GeminiTokenUsage usage, String finishReason) { }
    private record VerifiedEvidence(int page, String text) { }
    private record PageSlice(InspectedDocument document, Set<Integer> pages) { }

    private static final class PayloadAccumulator {
        private final Map<String, JsonNode> documents = new LinkedHashMap<>();
        private final Map<String, JsonNode> buildings = new LinkedHashMap<>();
        private final Map<String, JsonNode> units = new LinkedHashMap<>();
        private final Map<String, JsonNode> rentalConditions = new LinkedHashMap<>();
        private final Map<String, JsonNode> additionalAttributes = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, Integer> trustedExpectedCounts = new LinkedHashMap<>();
        private final Map<String, Set<Integer>> requiredSourcePagesByDocument = new LinkedHashMap<>();
        private final Map<String, Map<String, Set<Integer>>> requiredAttributesByDocument = new LinkedHashMap<>();
        private final Map<String, Set<String>> detectedColumnsByDocument = new LinkedHashMap<>();
        private boolean complete;
        private String nextCursor = "";

        PayloadAccumulator(List<InspectedDocument> inspectedDocuments) {
            for (InspectedDocument inspected : inspectedDocuments) {
                if (inspected.expectedUnitCount() != null) {
                    trustedExpectedCounts.put(
                            inspected.document().documentId(), inspected.expectedUnitCount());
                }
                requiredSourcePagesByDocument.put(
                        inspected.document().documentId(), requiredSourcePages(inspected));
                requiredAttributesByDocument.put(
                        inspected.document().documentId(), inspected.requiredAttributes());
                detectedColumnsByDocument.put(
                        inspected.document().documentId(), inspected.detectedTableColumns());
            }
        }

        void accept(JsonNode root) {
            root.path("documents").forEach(value -> documents.put(value.path("documentId").asText(), value));
            Map<String, String> buildingAliases = new LinkedHashMap<>();
            root.path("buildings").forEach(value -> {
                String responseId = value.path("buildingId").asText();
                String canonicalId = findEquivalentBuildingId(value);
                if (canonicalId == null) {
                    canonicalId = responseId;
                    buildings.put(canonicalId, value);
                }
                buildingAliases.put(responseId, canonicalId);
            });
            Map<String, String> unitAliases = new LinkedHashMap<>();
            root.path("units").forEach(value -> {
                ObjectNode normalized = (ObjectNode) value.deepCopy();
                String responseBuildingId = value.path("buildingId").asText();
                String buildingId = buildingAliases.getOrDefault(responseBuildingId, responseBuildingId);
                normalized.put("buildingId", buildingId);
                normalizeShiftedUnitColumns(normalized);
                JsonNode building = buildings.get(buildingId);
                String documentId = building == null
                        ? (documents.size() == 1 ? documents.keySet().iterator().next() : value.path("documentId").asText("UNKNOWN"))
                        : building.path("documentId").asText();
                Integer sourceIndex = sourceRowIndex(normalized.path("sourceRowLabel").asText(""));
                if (sourceIndex != null) {
                    normalized.put("extractionIndex", sourceIndex);
                    normalized.put("unitId", documentId + "/item-" + sourceIndex);
                }
                unitAliases.put(value.path("unitId").asText(), normalized.path("unitId").asText());
                units.put(documentId + ":" + normalized.path("extractionIndex").asInt(), normalized);
            });
            root.path("rentalConditions").forEach(value -> {
                ObjectNode normalized = (ObjectNode) value.deepCopy();
                normalized.put("unitId", unitAliases.getOrDefault(
                        value.path("unitId").asText(), value.path("unitId").asText()));
                rentalConditions.put(rentalConditionKey(normalized), normalized);
            });
            root.path("additionalAttributes").forEach(value -> {
                ObjectNode normalized = (ObjectNode) value.deepCopy();
                if ("BUILDING".equals(value.path("scope").asText())) {
                    String responseBuildingId = value.path("buildingId").asText();
                    normalized.put("buildingId", resolveBuildingId(
                            responseBuildingId, value.path("documentId").asText(), buildingAliases));
                }
                if ("UNIT".equals(value.path("scope").asText())) {
                    normalized.put("unitId", unitAliases.getOrDefault(
                            value.path("unitId").asText(), value.path("unitId").asText()));
                }
                additionalAttributes.put(normalized.path("scope").asText() + ":" + attributeTarget(normalized) + ":"
                        + normalized.path("name").asText() + ":" + normalized.path("value").asText(), normalized);
            });
            root.path("warnings").forEach(value -> warnings.add(value.asText()));
            complete = root.path("complete").asBoolean(false);
            nextCursor = root.path("nextCursor").asText("");
        }

        private Integer sourceRowIndex(String label) {
            Matcher matcher = Pattern.compile("^\\s*(\\d{1,4})(?:\\s|[.)번-]|$)").matcher(label);
            if (!matcher.find()) return null;
            int value = Integer.parseInt(matcher.group(1));
            return value > 0 && value <= 2_000 ? value : null;
        }

        private void normalizeShiftedUnitColumns(ObjectNode unit) {
            String unitNumber = unit.path("unitNumber").asText("");
            String buildingDong = unit.path("buildingDong").asText("");
            if (!isPlausibleUnitNumber(unitNumber) && isPlausibleUnitNumber(buildingDong)) {
                unit.put("unitNumber", buildingDong);
                unit.putNull("buildingDong");
            }
        }

        private boolean isPlausibleUnitNumber(String value) {
            return value != null && value.replaceAll("\\s+", "")
                    .matches("(?i)\\d{1,5}[A-Z가-힣]?호?");
        }

        private String resolveBuildingId(
                String responseBuildingId, String documentId, Map<String, String> buildingAliases
        ) {
            String direct = buildingAliases.get(responseBuildingId);
            if (direct != null) return direct;
            String ordinal = syntheticBuildingOrdinal(responseBuildingId);
            if (ordinal == null) return responseBuildingId;
            return buildings.entrySet().stream()
                    .filter(entry -> documentId.equals(entry.getValue().path("documentId").asText()))
                    .filter(entry -> ordinal.equals(syntheticBuildingOrdinal(entry.getKey())))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(responseBuildingId);
        }

        private String findEquivalentBuildingId(JsonNode candidate) {
            String documentId = candidate.path("documentId").asText();
            String candidateAddress = firstIdentity(candidate, "roadAddress", "lotAddress", "address");
            String addressMatch = buildings.entrySet().stream()
                    .filter(entry -> documentId.equals(entry.getValue().path("documentId").asText()))
                    .filter(entry -> !candidateAddress.isBlank() && candidateAddress.equals(firstIdentity(
                            entry.getValue(), "roadAddress", "lotAddress", "address")))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (addressMatch != null) return addressMatch;

            String ordinal = syntheticBuildingOrdinal(candidate.path("buildingId").asText());
            if (ordinal == null) return null;
            return buildings.entrySet().stream()
                    .filter(entry -> documentId.equals(entry.getValue().path("documentId").asText()))
                    .filter(entry -> ordinal.equals(syntheticBuildingOrdinal(entry.getKey())))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }

        private String syntheticBuildingOrdinal(String buildingId) {
            Matcher matcher = Pattern.compile("(?i)(?:building|bldg)[-_/]?(\\d+)$").matcher(buildingId);
            return matcher.find() ? matcher.group(1) : null;
        }

        private String firstIdentity(JsonNode node, String... fields) {
            for (String field : fields) {
                String value = node.path(field).asText("").replaceAll("[^0-9A-Za-z가-힣]", "");
                if (!value.isBlank()) return value;
            }
            return "";
        }

        List<JsonNode> conditionsFor(String unitId) {
            return rentalConditions.values().stream()
                    .filter(value -> unitId.equals(value.path("unitId").asText()))
                    .toList();
        }

        private String rentalConditionKey(JsonNode condition) {
            return condition.path("unitId").asText() + ":" + canonicalConditionGroup(condition);
        }

        private String canonicalConditionGroup(JsonNode condition) {
            String code = condition.path("conditionCode").asText("")
                    .replaceAll("[^0-9A-Za-z가-힣]", "").toUpperCase();
            Matcher trailingNumber = Pattern.compile("(\\d+)$").matcher(code);
            if (trailingNumber.find()) return trailingNumber.group(1);
            String name = compactAttributeName(condition.path("conditionName").asText());
            if (name.contains("1순위") || name.contains("50이하")) return "1";
            if (name.contains("2순위") || name.contains("3순위") || name.contains("50초과")) return "2";
            return name.isBlank() ? code : name;
        }

        List<JsonNode> attributesFor(String scope, String targetId) {
            return additionalAttributes.values().stream()
                    .filter(value -> scope.equals(value.path("scope").asText()))
                    .filter(value -> targetId.equals(attributeTarget(value)))
                    .toList();
        }

        private static String attributeTarget(JsonNode value) {
            return switch (value.path("scope").asText()) {
                case "DOCUMENT" -> value.path("documentId").asText();
                case "BUILDING" -> value.path("buildingId").asText();
                case "UNIT" -> value.path("unitId").asText();
                default -> "";
            };
        }

        boolean serverVerifiedComplete() {
            if (documents.isEmpty() && trustedExpectedCounts.isEmpty()) return complete;
            boolean hasPositiveExpectedCount = false;
            for (Map.Entry<String, Integer> entry : expectedCounts().entrySet()) {
                String documentId = entry.getKey();
                int expected = entry.getValue();
                if (expected <= 0) continue;
                hasPositiveExpectedCount = true;
                Set<Integer> indexes = indexes(documentId);
                for (int value = 1; value <= expected; value++) {
                    if (!indexes.contains(value)) return false;
                }
            }
            boolean rowsComplete = hasPositiveExpectedCount || complete;
            return rowsComplete && missingSourcePages().isEmpty()
                    && inconsistentFieldRows().isEmpty() && missingRequiredAttributes().isEmpty();
        }

        String continuationDirective(int limit) {
            List<String> requests = new ArrayList<>();
            int remaining = limit;
            for (Map.Entry<String, Integer> entry : expectedCounts().entrySet()) {
                if (remaining <= 0) break;
                String documentId = entry.getKey();
                int expected = entry.getValue();
                if (expected <= 0) continue;
                Set<Integer> existing = indexes(documentId);
                List<Integer> missing = new ArrayList<>();
                for (int value = 1; value <= expected && missing.size() < remaining; value++) {
                    if (!existing.contains(value)) missing.add(value);
                }
                if (!missing.isEmpty()) {
                    requests.add("documentId=" + documentId + ", missingExtractionIndexes=" + missing);
                    remaining -= missing.size();
                }
            }
            if (!requests.isEmpty()) return String.join("\n", requests);
            Map<String, List<Integer>> missingPages = missingSourcePages();
            if (!missingPages.isEmpty()) {
                return "아래 원문 페이지에는 공급행이 있지만 현재 누적 결과에는 해당 페이지의 행이 없습니다. "
                        + "해당 페이지의 원문 연번과 모든 행을 다시 반환하세요.\n"
                        + missingPages.entrySet().stream()
                        .map(entry -> "documentId=" + entry.getKey() + ", missingSourcePages=" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            List<String> fieldRequests = inconsistentFieldRows().entrySet().stream()
                    .limit(limit)
                    .map(entry -> "unit=" + entry.getKey() + ", missingFields=" + entry.getValue())
                    .toList();
            if (!fieldRequests.isEmpty()) {
                return "아래 행은 같은 건물·공급표의 다른 행에는 있는 열이 누락됐습니다. 각 행 전체를 다시 반환하세요.\n"
                        + String.join("\n", fieldRequests);
            }
            Map<String, List<String>> missingAttributes = missingRequiredAttributes();
            if (!missingAttributes.isEmpty()) {
                return "PDFBox가 원문에서 아래 주택 추가정보 표제를 확인했지만 응답에 없습니다. 원문 값을 찾아 "
                        + "고정 필드와 additionalAttributes에 보완하세요.\n"
                        + missingAttributes.entrySet().stream()
                        .map(entry -> "documentId=" + entry.getKey() + ", missingAdditionalAttributes=" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            if (!complete && !nextCursor.isBlank()) {
                return "AI nextCursor=" + nextCursor + " 바로 다음 원문 행부터 반환하세요.";
            }
            return "";
        }

        private Map<String, Integer> expectedCounts() {
            Map<String, Integer> result = new LinkedHashMap<>();
            documents.values().forEach(document -> result.put(
                    document.path("documentId").asText(),
                    document.path("expectedUnitCount").asInt(0)));
            trustedExpectedCounts.forEach((documentId, expected) -> result.merge(
                    documentId, expected, Math::max));
            return result;
        }

        private Set<Integer> requiredSourcePages(InspectedDocument inspected) {
            int expected = inspected.expectedUnitCount() == null ? 0 : inspected.expectedUnitCount();
            if (expected <= 0) return Set.of();
            Set<Integer> result = new TreeSet<>();
            inspected.pageTexts().forEach((page, text) -> {
                if (!inspected.relevantPages().contains(page) || text == null || text.isBlank()) return;
                Set<Integer> indexes = new HashSet<>();
                Matcher matcher = LEADING_ROW_NUMBER.matcher(text);
                while (matcher.find()) {
                    int value = Integer.parseInt(matcher.group(1));
                    if (value >= 1 && value <= expected) indexes.add(value);
                }
                // 표가 이어지는 페이지는 여러 연번이 있고, 마지막 한 행만 있는 페이지는 N번으로 확인한다.
                if (indexes.size() >= 2 || indexes.contains(expected)) result.add(page);
            });
            return Set.copyOf(result);
        }

        private Map<String, List<Integer>> missingSourcePages() {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            requiredSourcePagesByDocument.forEach((documentId, requiredPages) -> {
                Set<Integer> extractedPages = new HashSet<>();
                String prefix = documentId + ":";
                units.forEach((key, unit) -> {
                    if (!key.startsWith(prefix)) return;
                    int page = unit.path("page").asInt(0);
                    if (page > 0) extractedPages.add(page);
                });
                List<Integer> missing = requiredPages.stream()
                        .filter(page -> !extractedPages.contains(page))
                        .sorted()
                        .toList();
                if (!missing.isEmpty()) result.put(documentId, missing);
            });
            return result;
        }

        private Map<String, List<String>> inconsistentFieldRows() {
            Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
            Map<String, String> groupDocuments = new LinkedHashMap<>();
            units.forEach((key, unit) -> {
                String documentId = key.substring(0, key.lastIndexOf(':'));
                String buildingId = unit.path("buildingId").asText("NO_BUILDING");
                String sourceTableId = unit.path("sourceTableId").asText("");
                if (sourceTableId.isBlank()) sourceTableId = documentId + "|" + buildingId;
                String groupKey = documentId + "|" + sourceTableId;
                groupDocuments.put(groupKey, documentId);
                groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>())
                        .add(unit);
            });

            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<JsonNode>> groupEntry : groups.entrySet()) {
                List<JsonNode> group = groupEntry.getValue();
                if (group.size() < 2) continue;
                Set<String> detectedColumns = detectedColumnsByDocument.getOrDefault(
                        groupDocuments.get(groupEntry.getKey()), Set.of());
                for (String field : CONSISTENT_UNIT_FIELDS) {
                    if (!detectedColumns.isEmpty() && !detectedColumns.contains(field)) continue;
                    boolean provided = group.stream().anyMatch(unit -> hasConcreteFieldValue(unit, field));
                    if (!provided) continue;
                    for (JsonNode unit : group) {
                        if (hasFieldValue(unit, field)) continue;
                        String unitKey = unit.path("unitId").asText(
                                "extractionIndex=" + unit.path("extractionIndex").asInt());
                        result.computeIfAbsent(unitKey, ignored -> new ArrayList<>()).add(field);
                    }
                }
                boolean groupHasMultipleConditions = group.stream().anyMatch(unit -> {
                    String unitId = unit.path("unitId").asText();
                    return rentalConditions.values().stream()
                            .filter(condition -> unitId.equals(condition.path("unitId").asText()))
                            .map(this::canonicalConditionGroup)
                            .distinct()
                            .count() >= 2;
                });
                if (detectedColumns.contains("multipleRentalConditions") || groupHasMultipleConditions) {
                    for (JsonNode unit : group) {
                        String unitId = unit.path("unitId").asText();
                        long conditionGroups = rentalConditions.values().stream()
                                .filter(condition -> unitId.equals(condition.path("unitId").asText()))
                                .map(this::canonicalConditionGroup)
                                .distinct()
                                .count();
                        if (conditionGroups >= 2) continue;
                        String unitKey = unit.path("unitId").asText(
                                "extractionIndex=" + unit.path("extractionIndex").asInt());
                        result.computeIfAbsent(unitKey, ignored -> new ArrayList<>())
                                .add("rentalConditions(원문 다중 조건 전체)");
                    }
                }
            }
            return result;
        }

        private Map<String, List<String>> missingRequiredAttributes() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            requiredAttributesByDocument.forEach((documentId, requirements) -> {
                for (String requirement : requirements.keySet()) {
                    if (!hasRequiredAttribute(documentId, requirement)) {
                        result.computeIfAbsent(documentId, ignored -> new ArrayList<>()).add(requirement);
                    }
                }
            });
            return result;
        }

        private boolean hasRequiredAttribute(String documentId, String requirement) {
            String compactRequirement = compactAttributeName(requirement);
            boolean attributeFound = additionalAttributes.values().stream()
                    .filter(attribute -> attributeBelongsToDocument(attribute, documentId))
                    .anyMatch(attribute -> {
                        String name = compactAttributeName(attribute.path("name").asText());
                        return name.contains(compactRequirement) || compactRequirement.contains(name)
                                || attributeAliasMatches(requirement, name);
                    });
            if (attributeFound) return true;
            return buildings.values().stream()
                    .filter(building -> documentId.equals(building.path("documentId").asText()))
                    .anyMatch(building -> switch (requirement) {
                        case "주차" -> building.path("totalParkingSpaces").asInt(0) > 0;
                        case "기계식 주차" -> building.hasNonNull("mechanicalParkingAvailable")
                                || building.path("mechanicalParkingSpaces").asInt(0) > 0;
                        default -> false;
                    });
        }

        private boolean attributeBelongsToDocument(JsonNode attribute, String documentId) {
            String scope = attribute.path("scope").asText();
            if ("DOCUMENT".equals(scope)) {
                return documentId.equals(attribute.path("documentId").asText());
            }
            if ("BUILDING".equals(scope)) {
                JsonNode building = buildings.get(attribute.path("buildingId").asText());
                return building != null && documentId.equals(building.path("documentId").asText());
            }
            if ("UNIT".equals(scope)) {
                String unitId = attribute.path("unitId").asText();
                return units.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith(documentId + ":")
                        && unitId.equals(entry.getValue().path("unitId").asText()));
            }
            return false;
        }

        private boolean attributeAliasMatches(String requirement, String compactName) {
            return switch (requirement) {
                case "주차" -> compactName.contains("주차대수") || compactName.contains("주차면수");
                case "건물 층수" -> compactName.contains("건물층") || compactName.equals("층수");
                case "건물 전체 세대수" -> compactName.contains("전체세대") || compactName.contains("총세대");
                case "주민공동시설 종류" -> compactName.contains("공동시설") || compactName.contains("커뮤니티");
                case "세대 구조" -> compactName.contains("세대구조") || compactName.contains("주택구조");
                case "기본 옵션" -> compactName.contains("기본옵션") || compactName.contains("구비품목");
                default -> false;
            };
        }

        private String compactAttributeName(String value) {
            return value == null ? "" : value.replaceAll("[^0-9A-Za-z가-힣]", "");
        }

        private boolean hasFieldValue(JsonNode unit, String field) {
            return hasConcreteFieldValue(unit, field) || verifiedNullFields(unit).contains(field);
        }

        private boolean hasConcreteFieldValue(JsonNode unit, String field) {
            JsonNode value = unit.path(field);
            if (!value.isMissingNode() && !value.isNull()
                    && (!value.isTextual() || !value.asText().isBlank())) {
                return true;
            }
            if (!"rentDeposit".equals(field) && !"monthlyRent".equals(field)) return false;
            String unitId = unit.path("unitId").asText();
            return rentalConditions.values().stream()
                    .filter(condition -> unitId.equals(condition.path("unitId").asText()))
                    .map(condition -> condition.path(field))
                    .anyMatch(conditionValue -> !conditionValue.isMissingNode() && !conditionValue.isNull());
        }

        Set<String> acceptedVerifiedNullFields(JsonNode target) {
            String targetId = target.path("unitId").asText();
            String targetTable = target.path("sourceTableId").asText("");
            String targetBuilding = target.path("buildingId").asText("NO_BUILDING");
            Set<String> requested = verifiedNullFields(target);
            Set<String> result = new HashSet<>();
            for (String field : requested) {
                boolean columnExists = units.values().stream()
                        .filter(unit -> !targetId.equals(unit.path("unitId").asText()))
                        .filter(unit -> sameTable(unit, targetTable, targetBuilding))
                        .anyMatch(unit -> hasConcreteFieldValue(unit, field));
                if (columnExists) result.add(field);
            }
            return Set.copyOf(result);
        }

        private boolean sameTable(JsonNode unit, String tableId, String buildingId) {
            String otherTable = unit.path("sourceTableId").asText("");
            if (!tableId.isBlank() || !otherTable.isBlank()) return tableId.equals(otherTable);
            return buildingId.equals(unit.path("buildingId").asText("NO_BUILDING"));
        }

        private Set<String> verifiedNullFields(JsonNode unit) {
            Set<String> result = new HashSet<>();
            String evidence = unit.path("evidence").asText("");
            unit.path("verifiedNullFields").forEach(value -> {
                String field = value.asText();
                if (hasFieldSpecificNullEvidence(field, evidence)) result.add(field);
            });
            return result;
        }

        private boolean hasFieldSpecificNullEvidence(String field, String evidence) {
            if (evidence == null || evidence.isBlank()) return false;
            List<String> aliases = switch (field) {
                case "unitNumber" -> List.of("호수", "호");
                case "buildingDong" -> List.of("동", "동명");
                case "floor" -> List.of("층");
                case "exclusiveArea" -> List.of("전용면적", "전용");
                case "commonArea" -> List.of("공용면적", "공용");
                case "rentalArea" -> List.of("임대면적");
                case "roomCount" -> List.of("방수", "방 수");
                case "rentDeposit" -> List.of("임대보증금", "보증금");
                case "monthlyRent" -> List.of("월임대료", "월 임대료", "월세");
                case "supplyCount" -> List.of("공급호수", "공급수", "세대수");
                case "elevatorAvailable" -> List.of("승강기", "엘리베이터");
                case "communityFacilityAvailable" -> List.of("공동시설", "커뮤니티시설");
                default -> List.of(field);
            };
            String nullMarker = "(?:-|해당\\s*없음|공고문\\s*확인|별도\\s*문의)";
            return aliases.stream().anyMatch(alias -> Pattern.compile(
                    "(?:" + Pattern.quote(alias) + ").{0,20}" + nullMarker
                            + "|" + nullMarker + ".{0,20}(?:" + Pattern.quote(alias) + ")",
                    Pattern.DOTALL).matcher(evidence).find());
        }

        Set<Integer> repairPages(String documentId, InspectedDocument inspected) {
            Set<Integer> pages = new TreeSet<>();
            Map<Integer, Integer> pageByIndex = new LinkedHashMap<>();
            String prefix = documentId + ":";
            units.forEach((key, unit) -> {
                if (!key.startsWith(prefix)) return;
                int index = unit.path("extractionIndex").asInt(0);
                int page = unit.path("page").asInt(0);
                if (index > 0 && page > 0) pageByIndex.put(index, page);
            });

            int expected = expectedCounts().getOrDefault(documentId, 0);
            Set<Integer> existing = indexes(documentId);
            for (int index = 1; index <= expected; index++) {
                if (existing.contains(index)) continue;
                Integer nearestPage = nearestKnownPage(index, pageByIndex);
                if (nearestPage != null) addPageWithNeighbors(pages, nearestPage, inspected.pages());
            }

            for (int missingPage : missingSourcePages().getOrDefault(documentId, List.of())) {
                addPageWithNeighbors(pages, missingPage, inspected.pages());
            }

            Set<String> incompleteUnitIds = inconsistentFieldRows().keySet();
            units.forEach((key, unit) -> {
                if (!key.startsWith(prefix)) return;
                if (!incompleteUnitIds.contains(unit.path("unitId").asText())) return;
                int page = unit.path("page").asInt(0);
                if (page > 0) addPageWithNeighbors(pages, page, inspected.pages());
            });
            Map<String, List<String>> missingAttributes = missingRequiredAttributes();
            for (String requirement : missingAttributes.getOrDefault(documentId, List.of())) {
                for (int page : inspected.requiredAttributes().getOrDefault(requirement, Set.of())) {
                    addPageWithNeighbors(pages, page, inspected.pages());
                }
            }
            if (pages.isEmpty()) pages.addAll(inspected.relevantPages());
            return Set.copyOf(pages);
        }

        private Integer nearestKnownPage(int targetIndex, Map<Integer, Integer> pageByIndex) {
            int bestDistance = Integer.MAX_VALUE;
            Integer bestPage = null;
            for (Map.Entry<Integer, Integer> entry : pageByIndex.entrySet()) {
                int distance = Math.abs(entry.getKey() - targetIndex);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestPage = entry.getValue();
                }
            }
            return bestPage;
        }

        private void addPageWithNeighbors(Set<Integer> pages, int page, int pageCount) {
            for (int candidate = Math.max(1, page - 1); candidate <= Math.min(pageCount, page + 1); candidate++) {
                pages.add(candidate);
            }
        }

        private Set<Integer> indexes(String documentId) {
            Set<Integer> result = new HashSet<>();
            String prefix = documentId + ":";
            units.forEach((key, value) -> {
                if (key.startsWith(prefix)) result.add(value.path("extractionIndex").asInt(0));
            });
            return result;
        }

        List<String> relevantPages() {
            List<String> result = new ArrayList<>();
            documents.values().forEach(document -> document.path("relevantPages").forEach(page ->
                    result.add(document.path("documentId").asText() + ":" + page.asInt())));
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allAttributes() {
            return additionalAttributes.values().stream()
                    .map(value -> (Map<String, Object>) JSON.convertValue(value, Map.class))
                    .toList();
        }

        Map<String, Object> toJson() {
            return Map.of(
                    "documents", documents.values(), "buildings", buildings.values(), "units", units.values(),
                    "rentalConditions", rentalConditions.values(),
                    "additionalAttributes", additionalAttributes.values(),
                    "complete", complete, "nextCursor", nextCursor, "warnings", warnings
            );
        }
    }
}
