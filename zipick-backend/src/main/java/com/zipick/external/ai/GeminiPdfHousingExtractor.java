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

    public static final String PROMPT_VERSION = "DIRECT_HOUSING_V19_BOUNDED_REPAIR";
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
        long inspectStarted = System.nanoTime();
        List<InspectedDocument> inspected;
        try {
            inspected = inspect(documents);
        } finally {
            GeminiPdfStageTiming.inspected(System.nanoTime() - inspectStarted);
        }
        log.info("Gemini PDF 문서 검사 시간: documentIds={}, 검사ms={}",
                documents.stream().map(GeminiPdfDocument::documentId).toList(),
                (System.nanoTime() - inspectStarted) / 1_000_000);
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
            if (accumulator.serverVerifiedComplete()) {
                accumulator = new PayloadAccumulator(inspected);
                resumed = false;
                log.warn("완료 캐시의 행 수가 충돌하여 원문부터 다시 분석합니다: documents={}", documents.size());
            }
        }

        var progress = resumed ? java.util.Optional.<String>empty() : cache.readProgress(cacheKey);
        if (progress.isPresent()) accumulator.accept(JSON.readTree(progress.get()));
        resumed = resumed || progress.isPresent();
        if (progress.isPresent() && accumulator.serverVerifiedComplete()) {
            GeminiPdfExtractionResult result = toResult(
                    accumulator, inspected, 0, true, GeminiTokenUsage.empty(), "PROGRESS_CACHE"
            );
            if (result.complete()) {
                cache.write(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
                cache.deleteProgress(cacheKey);
                log.info("Gemini PDF 부분 캐시가 새 완전성 규칙을 충족해 완료 처리: documents={}, units={}",
                        documents.size(), result.units().size());
                return result;
            }
            log.warn("Gemini PDF 부분 캐시의 최종 검증 실패: documents={}, warnings={}",
                    documents.size(), result.warnings());
            accumulator = new PayloadAccumulator(inspected);
            resumed = false;
            log.warn("부분 캐시의 행 수가 충돌하여 원문부터 다시 분석합니다: documents={}", documents.size());
        }
        GeminiTokenUsage usage = GeminiTokenUsage.empty();
        int calls = 0;
        String finishReason = "";

        String cachedContinuation = resumed
                ? accumulator.continuationDirective(MAX_MISSING_INDEXES_PER_REPAIR) : "";
        boolean needsInitialPass = !resumed
                || (!accumulator.serverVerifiedComplete() && cachedContinuation.isBlank());
        if (needsInitialPass) {
            List<PageSlice> slices = new ArrayList<>(pageSlices(inspected, accumulator, false));
            int truncatedSplits = 0;
            for (int index = 0; index < slices.size(); index++) {
                PageSlice slice = slices.get(index);
                boolean lastSlice = index == slices.size() - 1;
                String chunkDirective = slices.size() <= 1 ? "" : "페이지 분할 분석 " + (index + 1) + "/"
                        + slices.size() + "입니다. 전달된 원본 페이지만 추출하고, 원문 연번을 문서 전체 기준 "
                        + "extractionIndex로 사용하세요. " + (lastSlice
                        ? "마지막 묶음이므로 전달된 페이지를 모두 확인했다면 complete=true로 반환하세요."
                        : "아직 다른 페이지 묶음이 남아 있으므로 complete=false로 반환하세요.");
                ParsedApiResponse parsed;
                try {
                    parsed = executeSlice(slice, "", chunkDirective, accumulator, index > 0);
                    if ("MAX_TOKENS".equalsIgnoreCase(parsed.finishReason())
                            && slice.pages().size() > 1 && truncatedSplits < properties.maxContinuationCalls()) {
                        throw new TruncatedResponseException(parsed.usage());
                    }
                } catch (TruncatedResponseException exception) {
                    calls++;
                    usage = usage.plus(exception.usage);
                    if (slice.pages().size() < 2 || truncatedSplits >= properties.maxContinuationCalls()) throw exception;
                    List<Integer> pages = new ArrayList<>(new TreeSet<>(slice.pages()));
                    int middle = pages.size() / 2;
                    slices.remove(index);
                    slices.add(index, new PageSlice(slice.document(), Set.copyOf(pages.subList(middle, pages.size())), null, false));
                    slices.add(index, new PageSlice(slice.document(), Set.copyOf(pages.subList(0, middle)), null, false));
                    truncatedSplits++;
                    log.warn("Gemini 응답이 잘려 원문 페이지를 나누어 재분석합니다: documentId={}, pages={}, split={}",
                            slice.document().document().documentId(), pages.size(), truncatedSplits);
                    index--;
                    continue;
                }
                accumulator.accept(parsed.payload(), slices.size() > 1);
                if (slice.textOnly()) accumulator.recordTextOnly(slice.document().document().documentId(), slice.pages().iterator().next());
                usage = usage.plus(parsed.usage());
                finishReason = parsed.finishReason();
                calls++;
                cache.writeProgress(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
            }
        }

        int repairRounds = 0;
        int repairLimit = continuationLimit(inspected, accumulator);
        // 구체적인 누락 행·페이지 또는 잘린 출력이 있을 때만 소형 문서도 한 번 보정한다.
        // 추가정보 경고만 있거나 보정 횟수 설정이 0이면 추가 호출하지 않는다.
        String initialRepair = accumulator.continuationDirective(MAX_MISSING_INDEXES_PER_REPAIR);
        if (repairLimit == 0 && properties.maxContinuationCalls() > 0 && !initialRepair.isBlank()) {
            repairLimit = 1;
        }
        long previousProgress = accumulator.progressScore();
        String continuationDirective = accumulator.continuationDirective(MAX_MISSING_INDEXES_PER_REPAIR);
        while (!accumulator.serverVerifiedComplete()
                && !continuationDirective.isBlank()
                && repairRounds < repairLimit) {
            List<PageSlice> repairs = pageSlices(inspected, accumulator, true);
            for (PageSlice repair : repairs) {
                ParsedApiResponse parsed = executeSlice(
                        repair, continuationDirective, "", accumulator, true);
                if (continuationDirective.contains("missingPricingIndexes=")) {
                    accumulator.acceptPricingRepair(parsed.payload());
                } else {
                    accumulator.accept(parsed.payload());
                }
                if (repair.textOnly()) accumulator.recordTextOnly(repair.document().document().documentId(), repair.pages().iterator().next());
                usage = usage.plus(parsed.usage());
                finishReason = parsed.finishReason();
                calls++;
                cache.writeProgress(cacheKey, JSON.writeValueAsString(accumulator.toJson()));
            }
            repairRounds++;
            long currentProgress = accumulator.progressScore();
            if (currentProgress <= previousProgress) {
                log.info("Gemini PDF 보정 결과가 개선되지 않아 추가 호출을 중단합니다: documents={}, rounds={}",
                        documents.stream().map(GeminiPdfDocument::documentId).toList(), repairRounds);
                break;
            }
            previousProgress = currentProgress;
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

    /** 일반 PDF는 실행당 한 번만 호출하고, 행이 많은 대형 문서만 같은 실행에서 누락행을 이어 받는다. */
    private int continuationLimit(List<InspectedDocument> documents, PayloadAccumulator accumulator) {
        boolean largeDocument = documents.stream()
                .map(InspectedDocument::expectedUnitCount)
                .filter(java.util.Objects::nonNull)
                .anyMatch(count -> count >= properties.continuationMinExpectedUnits())
                || accumulator.maximumExpectedCount() >= properties.continuationMinExpectedUnits();
        if (largeDocument) return properties.maxContinuationCalls();
        return 0;
    }

    private ParsedApiResponse executeSlice(
            PageSlice slice,
            String continuationDirective,
            String chunkDirective,
            PayloadAccumulator accumulator,
            boolean continuation
    ) {
        long preparationStarted = System.nanoTime();
        String body;
        try {
            body = requestBody(List.of(slice.document()), Map.of(slice.document().document().documentId(), slice.pages()),
                    continuationDirective, accumulator.units.size(), accumulator, chunkDirective, slice.preparedPdf(), slice.textOnly());
        } finally {
            GeminiPdfStageTiming.prepared(System.nanoTime() - preparationStarted);
        }
        long preparedAt = System.nanoTime();
        String response;
        try {
            response = generateWithRetry(body, continuation);
        } finally {
            GeminiPdfStageTiming.apiWait(System.nanoTime() - preparedAt);
        }
        long completedAt = System.nanoTime();
        log.info("Gemini PDF 구간 시간: documentId={}, 준비ms={}, API대기ms={}, 준비된PDF재사용={}",
                slice.document().document().documentId(), (preparedAt - preparationStarted) / 1_000_000,
                (completedAt - preparedAt) / 1_000_000, slice.preparedPdf() != null);
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
        long started = System.nanoTime();
        try {
            List<PageSlice> result = new ArrayList<>();
            for (InspectedDocument document : documents) {
                Set<Integer> pages = repair
                        ? accumulator.repairPages(document.document().documentId(), document)
                        : document.relevantPages();
                result.addAll(splitPagesToLimit(document, pages));
            }
            log.info("Gemini PDF 페이지 준비 시간: documentIds={}, 분할수={}, 보정={}, 준비ms={}",
                    documents.stream().map(document -> document.document().documentId()).toList(),
                    result.size(), repair, (System.nanoTime() - started) / 1_000_000);
            return List.copyOf(result);
        } finally {
            GeminiPdfStageTiming.split(System.nanoTime() - started);
        }
    }

    private List<PageSlice> splitPagesToLimit(InspectedDocument document, Set<Integer> pages) {
        List<Integer> ordered = new ArrayList<>(new TreeSet<>(pages));
        List<PageSlice> result = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += properties.maxPagesPerRequest()) {
            int end = Math.min(ordered.size(), start + properties.maxPagesPerRequest());
            splitPagesToLimit(document, ordered.subList(start, end), result);
        }
        return List.copyOf(result);
    }

    private void splitPagesToLimit(
            InspectedDocument document,
            List<Integer> pages,
            List<PageSlice> target
    ) {
        Set<Integer> pageSet = Set.copyOf(pages);
        // 큰 원본을 통째로 메모리 버퍼에 복제하기 전에 나누어 최대 메모리 사용을 줄입니다.
        if (pages.size() > 4 && document.document().rawBytes().length > properties.maxFileBytes() * 2) {
            int middle = pages.size() / 2;
            splitPagesToLimit(document, pages.subList(0, middle), target);
            splitPagesToLimit(document, pages.subList(middle, pages.size()), target);
            return;
        }
        byte[] smallSubset = document.document().rawBytes().length <= 5 * 1024 * 1024
                ? subsetPdf(document.document().rawBytes(), pageSet, document.document().fileName()) : null;
        long subsetSize = smallSubset != null ? smallSubset.length
                : subsetPdfSize(document.document().rawBytes(), pageSet, document.document().fileName());
        if (subsetSize <= properties.maxFileBytes()) {
            // 작은 묶음만 재사용해 중복 PDF 생성을 줄이고 메모리는 제한한다.
            long retained = target.stream().mapToLong(slice -> slice.preparedPdf() == null ? 0 : slice.preparedPdf().length).sum();
            target.add(new PageSlice(document, pageSet,
                    subsetSize <= 1024 * 1024 && retained + subsetSize <= 4 * 1024 * 1024
                            ? (smallSubset != null ? smallSubset
                            : subsetPdf(document.document().rawBytes(), pageSet, document.document().fileName())) : null, false));
            return;
        }
        if (pages.size() == 1) {
            int page = pages.getFirst();
            String text = document.pageTexts().getOrDefault(page, "");
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("단일 페이지 PDF 크기 제한 초과 및 추출 가능한 텍스트 없음: "
                        + document.document().fileName() + ", page=" + page);
            }
            log.warn("대형 PDF 페이지를 텍스트만으로 분석합니다. 이미지 미분석: documentId={}, page={}",
                    document.document().documentId(), page);
            target.add(new PageSlice(document, pageSet, null, true));
            return;
        }
        int middle = pages.size() / 2;
        splitPagesToLimit(document, pages.subList(0, middle), target);
        splitPagesToLimit(document, pages.subList(middle, pages.size()), target);
    }

    /** 429·5xx 일시 장애는 호출 예산 안에서 짧게 재시도하고, 계속 실패하면 부분 캐시를 다음 실행에 남긴다. */
    private String generateWithRetry(String requestBody, boolean continuation) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                budget.acquire(continuation || attempt > 0);
            } catch (RuntimeException budgetFailure) {
                if (last != null) GeminiFinalFailureLogger.logFailure("extraction", last);
                throw budgetFailure;
            }
            try {
                return transport.generate(requestBody);
            } catch (RuntimeException exception) {
                last = exception;
                if (!isTransientApiFailure(exception) || attempt == MAX_TRANSIENT_RETRIES) {
                    GeminiFinalFailureLogger.logFailure("extraction", exception);
                    throw exception;
                }
                long delayMillis = 1_000L << attempt;
                log.warn("Gemini 일시 장애로 재시도합니다: attempt={}/{}, delayMs={}, reason={}",
                        attempt + 1, MAX_TRANSIENT_RETRIES, delayMillis, exception.getMessage());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    GeminiFinalFailureLogger.logFailure("extraction", exception);
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
        if (!properties.isConfigured()) {
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
        // 전체 문서의 번호를 합치면 신청 서류 번호와 목차가 공급행으로 오인된다.
        // 공급표 머리글에 인접하고 연속된 실제 데이터 행만 보수적으로 추정한다.
        String[] lines = text.split("\\R");
        int best = 0;
        for (int start = 0; start < lines.length; start++) {
            if (!hasSupplyTableHeader(String.join("\n", java.util.Arrays.copyOfRange(
                    lines, start, Math.min(lines.length, start + 3))))) continue;
            int next = 1;
            int gap = 0;
            for (int index = start + 1; index < lines.length && index <= start + 250; index++) {
                String line = lines[index].trim();
                if (line.matches("^\\d{1,3}[.)]\\s+.*")) break;
                Matcher row = Pattern.compile("^(\\d{1,4})\\s+(.+)$").matcher(line);
                if (row.matches() && Integer.parseInt(row.group(1)) == next
                        && row.group(2).matches(".*(?:\\d|특별시|광역시|특별자치|경기도|[시군구]\\s).*")) {
                    next++;
                    gap = 0;
                } else if (++gap > (next > 1 ? 8 : 12)) {
                    break;
                }
            }
            best = Math.max(best, next - 1);
        }
        return best >= 2 ? best : null;
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
        Set<Integer> selected = new TreeSet<>(legacyRelevantPages(pageTexts));
        // 공고문의 공급주택 요약은 표 연번이 없는 앞부분에 자주 있다.
        for (int page = 1; page <= Math.min(3, pageTexts.size()); page++) selected.add(page);
        for (Map.Entry<Integer, String> entry : pageTexts.entrySet()) {
            String compact = entry.getValue() == null ? "" : entry.getValue().replaceAll("\\s+", "");
            boolean housingAndConditions = (compact.contains("공급주택") || compact.contains("모집대상주택")
                    || compact.contains("주택단지개요"))
                    && (compact.contains("임대조건") || compact.contains("임대보증금")
                    || compact.contains("기본임대"));
            if (!housingAndConditions) continue;
            for (int page = Math.max(1, entry.getKey() - 1);
                 page <= Math.min(pageTexts.size(), entry.getKey() + 1); page++) selected.add(page);
        }
        return Set.copyOf(selected);
    }

    private Set<Integer> legacyRelevantPages(Map<Integer, String> pageTexts) {
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
        boolean multipleRentalConditions = containsMultipleRentalConditionColumns(pageTexts);
        if (multipleRentalConditions) result.add("multipleRentalConditions");
        return Set.copyOf(result);
    }

    /** 자격 설명의 순위를 임대조건 열로 오인하지 않고, 금액 표제 주변에서만 다중 조건을 감지한다. */
    private boolean containsMultipleRentalConditionColumns(Map<Integer, String> pageTexts) {
        for (String pageText : pageTexts.values()) {
            if (pageText == null || pageText.isBlank()) continue;
            String[] lines = pageText.split("\\R");
            for (int index = 0; index < lines.length; index++) {
                String header = lines[index].replaceAll("\\s+", "");
                if (!header.contains("임대조건") && !header.contains("임대보증금")
                        && !header.contains("월임대료") && !header.contains("월세")) continue;
                StringBuilder nearby = new StringBuilder();
                int from = Math.max(0, index - 2);
                int to = Math.min(lines.length - 1, index + 6);
                for (int line = from; line <= to; line++) {
                    nearby.append(lines[line].replaceAll("\\s+", ""));
                }
                String window = nearby.toString();
                if ((window.contains("1순위") && window.contains("2순위"))
                        || (window.contains("50%이하") && window.contains("50%초과"))) return true;
            }
        }
        return false;
    }

    private String requestBody(
            List<InspectedDocument> documents,
            Map<String, Set<Integer>> pageOverrides,
            String continuationDirective,
            int storedUnitCount,
            PayloadAccumulator accumulator,
            String chunkDirective,
            byte[] preparedPdf,
            boolean textOnly
    ) {
        List<Map<String, Object>> parts = new ArrayList<>();
        long requestBytes = 0;
        for (InspectedDocument inspected : documents) {
            GeminiPdfDocument document = inspected.document();
            Set<Integer> requestedPages = pageOverrides.getOrDefault(
                    document.documentId(), inspected.relevantPages());
            if (requestedPages.isEmpty()) requestedPages = inspected.relevantPages();
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
                    : ", 공급행수=" + inspected.expectedUnitCount() + "(서버가 확인한 최소 후보 수)")));
            if (textOnly) {
                int page = requestedPages.iterator().next();
                parts.add(Map.of("text", "원본 PDF " + page + "페이지의 추출 텍스트입니다. 이 페이지의 이미지와 도면은 제공되지 않았으므로 시각 내용을 추측하지 마세요.\n"
                        + inspected.pageTexts().getOrDefault(page, "")));
            } else {
                byte[] requestPdf = preparedPdf != null ? preparedPdf
                        : subsetPdf(document.rawBytes(), requestedPages, document.fileName());
                requestBytes += requestPdf.length;
                if (requestBytes > properties.maxFileBytes()) {
                    throw new IllegalArgumentException("선별 페이지 PDF 크기 제한 초과: " + document.fileName());
                }
                parts.add(Map.of("inlineData", Map.of("mimeType", "application/pdf",
                        "data", Base64.getEncoder().encodeToString(requestPdf))));
            }
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

    private long subsetPdfSize(byte[] sourceBytes, Set<Integer> originalPages, String fileName) {
        try (PDDocument source = Loader.loadPDF(sourceBytes); PDDocument target = new PDDocument()) {
            for (int page : new TreeSet<>(originalPages)) {
                if (page >= 1 && page <= source.getNumberOfPages()) target.importPage(source.getPage(page - 1));
            }
            if (target.getNumberOfPages() == 0) return sourceBytes.length;
            class Counter extends java.io.OutputStream {
                long size;
                @Override public void write(int b) { size++; }
                @Override public void write(byte[] b, int off, int len) { size += len; }
            }
            Counter counter = new Counter();
            target.save(counter);
            return counter.size;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Gemini 전달용 PDF 페이지 크기 확인 실패: " + fileName, exception);
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
                - 문서 메타데이터의 공급행수는 서버가 확인한 최소 후보 수입니다. 원문에 더 많은 행이 있다면 모두 추출하고
                  실제 행 수를 expectedUnitCount에 기록하세요. 힌트에 맞추려고 결과를 자르거나 행을 지어내지 마세요.
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
                표의 열마다 단위를 확인하세요. 보증금이 천원 단위이면 1,000을 곱하고 월 임대료가 원 단위이면 그대로 기록하세요.
                기숙사비·관리비는 월 임대료가 아닙니다. 임대료가 공란 또는 '-'인 경우 monthlyRent를 비우고, 기숙사비는 별도 추가정보로 기록하세요.
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
                supplyCount에는 이번 공고의 모집호수 또는 모집 예비자 수만 기록하세요. 단지 전체 세대수는 공급 수가 아닙니다.
                개별 동·호 목록이 없어도 원문에 특정 단지의 주소와 모집 주택형 또는 면적별 임대조건이 명시되면
                건물과 주택형을 연결해 주택형마다 units를 한 행씩 반환하세요. 해당 동·호가 없으면 그 값만 비워두세요.
                전세임대처럼 입주자가 나중에 직접 주택을 찾거나, 공급주택 정보가 별도 첨부에만 있어
                이 PDF에서 실제 단지·주택형을 확인할 수 없는 경우에는 units를 만들거나 주소를 추측하지 마세요.
                그런 공고문에서는 원문에 명시된 공급대상 주택 수,
                기본 임대기간, 재계약 가능 횟수, 최장 거주기간, 임대조건, 신청 시작·마감, 신청 방식을 DOCUMENT 범위의
                additionalAttributes로 기록하세요.
                주차면수·주차대수가 숫자로 명시되면 additionalAttributes뿐 아니라 buildings.totalParkingSpaces에도 숫자로 기록하세요. 주차장 공용면적은 주차 대수가 아니며 면적이 0이라는 이유로 주차 대수를 0으로 기록하지 마세요.
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
        JsonNode usage = envelope.path("usageMetadata");
        GeminiTokenUsage tokenUsage = new GeminiTokenUsage(
                usage.path("promptTokenCount").asLong(0),
                usage.path("candidatesTokenCount").asLong(0),
                usage.path("thoughtsTokenCount").asLong(0),
                usage.path("totalTokenCount").asLong(0));
        String finishReason = candidate.path("finishReason").asText("");
        if (text.isEmpty()) {
            if ("MAX_TOKENS".equalsIgnoreCase(finishReason)) throw new TruncatedResponseException(tokenUsage);
            throw new IllegalStateException("Gemini PDF 분석 JSON이 비어 있습니다.");
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(text.toString());
        } catch (RuntimeException exception) {
            if ("MAX_TOKENS".equalsIgnoreCase(finishReason)) throw new TruncatedResponseException(tokenUsage);
            throw new IllegalStateException(
                    "Gemini PDF 분석 JSON이 잘렸거나 올바르지 않습니다. finishReason=" + finishReason,
                    exception
            );
        }
        return new ParsedApiResponse(
                payload,
                tokenUsage,
                finishReason
        );
    }

    private static final class TruncatedResponseException extends IllegalStateException {
        private final GeminiTokenUsage usage;
        private TruncatedResponseException(GeminiTokenUsage usage) {
            super("Gemini PDF 분석 JSON이 잘렸거나 출력 토큰 한도를 초과했습니다. finishReason=MAX_TOKENS");
            this.usage = usage;
        }
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
                    documents, List.copyOf(accumulator.units.values()), ++index
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
        if (trustedExpected != null && modelExpected < trustedExpected) {
            warnings.add("모델 예상 행 수 불일치: server=" + trustedExpected + ", model=" + modelExpected);
        }
        boolean countMatches = trustedExpected == null
                ? expected == 0 || expected == units.size()
                : expected == units.size();
        if (!countMatches) warnings.add("예상 행 수 불일치: expected=" + expected + ", extracted=" + units.size());
        boolean sequenceComplete = validateSequences(accumulator.documents, documents, indexesByDocument, warnings);
        boolean areaRowsComplete = validateAreaVariants(accumulator, documents, warnings);
        Map<String, List<Integer>> missingPages = accumulator.missingSourcePages();
        if (!missingPages.isEmpty()) warnings.add("공급표 원문 페이지 누락: " + missingPages);
        Map<String, List<String>> inconsistentFields = accumulator.inconsistentFieldRows();
        if (!inconsistentFields.isEmpty()) {
            warnings.add("선택 필드 일부 누락(결과는 사용): " + inconsistentFields);
        }
        Map<String, List<String>> missingAttributes = accumulator.missingRequiredAttributes();
        if (!missingAttributes.isEmpty()) {
            warnings.add("추가정보 일부 누락(결과는 사용): " + missingAttributes);
        }
        Map<String, List<Integer>> missingPricing = accumulator.missingPricing();
        if (!missingPricing.isEmpty()) warnings.add("원문 임대금액 표가 있으나 금액이 추출되지 않았습니다: " + missingPricing);
        boolean tokenCut = "MAX_TOKENS".equalsIgnoreCase(finishReason);
        if (tokenCut) warnings.add("출력 토큰 한도에 도달했습니다.");
        if (!accumulator.serverVerifiedComplete() && warnings.isEmpty()) {
            warnings.add("문서 완료 여부를 확인하지 못했습니다.");
        }
        boolean complete = accumulator.serverVerifiedComplete() && countMatches && sequenceComplete && areaRowsComplete && !tokenCut;
        if (!complete && calls >= 1 + properties.maxContinuationCalls()) {
            warnings.add("허용된 후속 호출 횟수 안에 추출을 완료하지 못했습니다.");
        }
        return new GeminiPdfExtractionResult(
                units, complete, accumulator.nextCursor, expected, calls, fromCache, usage,
                accumulator.relevantPages(), accumulator.allAttributes(), warnings
        );
    }

    private boolean validateAreaVariants(PayloadAccumulator accumulator, List<InspectedDocument> documents, List<String> warnings) {
        boolean valid = true;
        for (InspectedDocument document : documents) {
            String id = document.document().documentId();
            List<AreaVariant> reported = accumulator.units.values().stream()
                    .filter(unit -> unit.path("sourceTableId").asText("").startsWith(id + "/"))
                    .map(unit -> new AreaVariant(nullableDecimal(unit, "exclusiveArea"),
                            nullableDecimal(unit, "commonArea"), nullableDecimal(unit, "rentalArea")))
                    .filter(value -> value.exclusive() != null && value.common() != null && value.total() != null)
                    .toList();
            if (reported.isEmpty()) continue;
            for (String page : document.pageTexts().values()) {
                List<AreaVariant> missing = missingAreaVariants(page, reported);
                if (!missing.isEmpty()) {
                    valid = false;
                    warnings.add("원문 공급표에 추출되지 않은 면적 행이 있습니다: documentId=" + id + ", rows=" + missing);
                }
            }
        }
        return valid;
    }

    record AreaVariant(BigDecimal exclusive, BigDecimal common, BigDecimal total) { }

    static List<AreaVariant> missingAreaVariants(String pageText, List<AreaVariant> reported) {
        if (pageText == null || reported == null || reported.isEmpty()) return List.of();
        Matcher heading = Pattern.compile("형별\\s*공급계획").matcher(pageText);
        if (!heading.find()) return List.of();
        int end = pageText.indexOf('※', heading.start());
        String table = pageText.substring(heading.start(), end < 0 ? Math.min(pageText.length(), heading.start() + 5000) : end);
        String header = table.replaceAll("\\s+", "");
        if (!header.contains("전용") || !header.contains("공용") || !header.contains("합계")) return List.of();
        Matcher pairs = Pattern.compile("(?=(?<![\\d.])(\\d{1,3}\\.\\d{2,4})(?:\\s+(?:[-–—]\\s+)*)+(\\d{1,3}\\.\\d{2,4})(?![\\d.]))").matcher(table);
        List<AreaVariant> found = new ArrayList<>();
        Set<BigDecimal> areas = reported.stream().map(AreaVariant::exclusive).collect(java.util.stream.Collectors.toSet());
        while (pairs.find()) {
            BigDecimal common = new BigDecimal(pairs.group(1));
            BigDecimal total = new BigDecimal(pairs.group(2));
            for (BigDecimal area : areas) {
                if (area.add(common).subtract(total).abs().compareTo(new BigDecimal("0.02")) <= 0) {
                    found.add(new AreaVariant(area, common, total));
                }
            }
        }
        List<AreaVariant> missing = new ArrayList<>();
        for (BigDecimal area : areas) {
            boolean anchored = found.stream().anyMatch(candidate -> reported.stream().anyMatch(value -> sameAreaVariant(candidate, value)) && candidate.exclusive().compareTo(area) == 0);
            if (!anchored) continue;
            found.stream().filter(candidate -> candidate.exclusive().compareTo(area) == 0)
                    .filter(candidate -> reported.stream().noneMatch(value -> sameAreaVariant(candidate, value)))
                    .filter(candidate -> missing.stream().noneMatch(value -> sameAreaVariant(candidate, value)))
                    .forEach(missing::add);
        }
        return List.copyOf(missing);
    }

    private static boolean sameAreaVariant(AreaVariant a, AreaVariant b) {
        return a.exclusive().compareTo(b.exclusive()) == 0 && a.common().compareTo(b.common()) == 0
                && a.total().compareTo(b.total()) == 0;
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
            List<JsonNode> allUnits,
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
        raw.put("sourceRowLabel", unit.path("sourceRowLabel").asText(""));
        raw.put("buildingId", unit.path("buildingId").asText(""));
        raw.put("verifiedNullFields", verifiedNullFields);
        String modelEvidence = unit.path("evidence").asText("");
        VerifiedEvidence verifiedEvidence = verifyEvidence(
                documentId, modelEvidence, unit.path("page").asInt(0), documents);
        String evidence = verifiedEvidence.text();
        int evidencePage = verifiedEvidence.page();
        boolean textOnlyPage = documentAttributes.stream().anyMatch(attribute ->
                "이미지 미분석 페이지".equals(attribute.path("name").asText())
                        && (attribute.path("page").asInt() == evidencePage
                        || attribute.path("page").asInt() == unit.path("page").asInt()));
        BigDecimal extractedArea = nullableDecimal(unit, "exclusiveArea");
        Long extractedSupplyCount = nullableLong(unit, "supplyCount");
        String gender = text(unit, "genderEligibility", null);
        boolean pamphlet = documentMetadata != null && documentMetadata.path("documentType").asText("").contains("팸플릿");
        if (pamphlet && extractedSupplyCount != null
                && (evidence == null || !evidence.matches("(?s).*(?:모집|공급호수|공급세대).*"))) {
            raw.put("unverifiedPamphletHouseholdCount", extractedSupplyCount);
            extractedSupplyCount = null;
        }
        if (textOnlyPage) {
            if (!explicitAreaInEvidence(evidence, extractedArea)) extractedArea = null;
            if (evidence == null || !evidence.matches("(?s).*(?:모집|공급호수|공급세대).*")) extractedSupplyCount = null;
            if (gender != null && !gender.matches(".*(?:남성|여성|남자|여자|혼성).*")) gender = null;
            raw.put("textOnlyFieldValidation", "원문 근거가 부족한 면적·모집수·성별은 저장하지 않음");
        }
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
        appendTextAttribute(normalizedUnitAttributes, "성별용도", gender,
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
        Long modelDeposit = firstNonNull(nullableLong(unit, "rentDeposit"), firstConditionDeposit(conditions));
        Long modelRent = firstNonNull(nullableLong(unit, "monthlyRent"), firstConditionMonthlyRent(conditions));
        Long correctedDeposit = verifiedThousandWonDeposit(modelEvidence, modelDeposit, modelRent, documents.stream()
                .filter(value -> value.document().documentId().equals(documentId))
                .map(InspectedDocument::pageTexts).findFirst().orElse(Map.of()));
        if (conditions.isEmpty()) {
            conditions.addAll(verifiedConversionConditionsForBase(text(unit, "housingType", null), modelDeposit, modelRent, documents.stream()
                    .filter(value -> value.document().documentId().equals(documentId))
                    .map(InspectedDocument::pageTexts).findFirst().orElse(Map.of())));
        }
        DormitoryFee dormitoryFee = verifiedDormitoryFee(complexName, documents.stream()
                .filter(value -> value.document().documentId().equals(documentId))
                .map(InspectedDocument::pageTexts).findFirst().orElse(Map.of()));
        if (dormitoryFee != null) {
            appendLongAttribute(normalizedUnitAttributes, "기숙사비", dormitoryFee.amount(), dormitoryFee.page(), dormitoryFee.evidence());
            raw.put("verifiedDormitoryFee", Map.of("amount", dormitoryFee.amount(), "page", dormitoryFee.page(), "evidence", dormitoryFee.evidence()));
        }
        String provinceName = firstNonBlank(text(unit, "provinceName", null), text(building, "provinceName", null));
        String districtName = firstNonBlank(text(unit, "districtName", null), text(building, "districtName", null));
        String lotAddress = longestNonBlank(text(unit, "lotAddress", null), text(building, "lotAddress", null));
        String roadAddress = longestNonBlank(text(unit, "roadAddress", null), text(building, "roadAddress", null));
        String generalAddress = longestNonBlank(text(unit, "address", null), text(building, "address", null));
        roadAddress = completeRoadAddress(districtName, roadAddress, generalAddress, evidence);
        roadAddress = sanitizeRoadAddress(roadAddress, complexName);
        generalAddress = sanitizeRoadAddress(generalAddress, complexName);
        String buildingDong = text(unit, "buildingDong", null);
        String unitNumber = text(unit, "unitNumber", null);
        String pageText = documents.stream().filter(value -> value.document().documentId().equals(documentId))
                .map(value -> value.pageTexts().getOrDefault(unit.path("page").asInt(0), ""))
                .findFirst().orElse("");
        Long sharedCount = verifiedSharedSupplyCount(unit, allUnits, pageText);
        if (sharedCount != null) {
            raw.put("verifiedSharedSupplyCount", sharedCount);
            appendLongAttribute(normalizedUnitAttributes, "주택형 공통 예비자 모집수", sharedCount, evidencePage, evidence);
        }
        if ((unitNumber == null || unitNumber.isBlank()) && isBuildingOnlySupplyTable(pageText)) {
            raw.put("verifiedNoUnitNumber", true);
        }
        if ((buildingDong == null || buildingDong.isBlank()) && belongsToBuildingColumn(pageText, unitNumber, modelEvidence)) {
            buildingDong = unitNumber;
            unitNumber = null;
            raw.put("verifiedNoUnitNumber", true);
        }
        // 일반 주소 뒤에 지번 주소가 함께 붙은 경우에도 좌표 변환에는 완전한 도로명주소만 사용합니다.
        String address = firstNonBlank(roadAddress, generalAddress, lotAddress);
        return new DetailedHousingUnit(
                "GEMINI:" + hash.substring(0, Math.min(16, hash.length())) + ":" + unitId,
                complexName,
                provinceName, districtName, address,
                buildingDong, unitNumber, nullableInt(unit, "floor"),
                extractedArea, nullableInt(unit, "roomCount"),
                firstNonBlank(text(unit, "housingType", null), text(building, "housingType", null),
                        text(unit, "supplyType", null)),
                text(unit, "supplyType", null),
                correctedDeposit != null ? correctedDeposit : modelDeposit,
                dormitoryFee != null && dormitoryFee.amount().equals(firstNonNull(nullableLong(unit, "monthlyRent"), firstConditionMonthlyRent(conditions)))
                        ? null : firstNonNull(nullableLong(unit, "monthlyRent"), firstConditionMonthlyRent(conditions)),
                JSON.writeValueAsString(raw),
                lotAddress, roadAddress,
                extractionIndex, text(unit, "supplyStatus", null),
                firstNonBlank(text(unit, "nearbySubwayStation", null), text(building, "nearbySubwayStation", null)),
                firstNonNull(nullableDecimal(unit, "subwayWalkDistanceKm"),
                        nullableDecimal(building, "subwayWalkDistanceKm")),
                firstNonNull(nullableBoolean(unit, "elevatorAvailable"), nullableBoolean(building, "elevatorAvailable")),
                firstNonNull(nullableBoolean(unit, "communityFacilityAvailable"),
                        nullableBoolean(building, "communityFacilityAvailable")),
                verifiedParkingCount(firstNonNull(nullableInt(building, "totalParkingSpaces"),
                        integerFromAttributes(buildingAttributes, "주차")), buildingAttributes),
                nullableBoolean(building, "mechanicalParkingAvailable"),
                nullableInt(building, "mechanicalParkingSpaces"), sharedCount != null ? null : extractedSupplyCount, conditions
        );
    }

    static boolean explicitAreaInEvidence(String evidence, BigDecimal area) {
        if (evidence == null || area == null) return false;
        String number = Pattern.quote(area.stripTrailingZeros().toPlainString());
        return Pattern.compile("(?<![0-9.])" + number + "(?:\\.0+)?\\s*(?:㎡|m²|m2|제곱미터)",
                Pattern.CASE_INSENSITIVE).matcher(evidence).find();
    }

    static Integer verifiedParkingCount(Integer count, List<JsonNode> attributes) {
        if (count == null || count != 0) return count;
        // 주차장 면적 0㎡와 주차 가능 대수 0대를 혼동하지 않도록 명시된 단위가 필요합니다.
        return attributes.stream().filter(attribute -> attribute.path("name").asText("").contains("주차"))
                .anyMatch(attribute -> (attribute.path("value").asText("") + " "
                        + attribute.path("evidence").asText(""))
                        .matches("(?s).*0\\s*(?:대|면)(?:\\s|[.,)）]|$).*")) ? count : null;
    }

    static Long verifiedSharedSupplyCount(JsonNode unit, List<JsonNode> allUnits, String pageText) {
        if (!isBuildingOnlySupplyTable(pageText)) return null;
        BigDecimal area = nullableDecimal(unit, "exclusiveArea");
        String tableId = unit.path("sourceTableId").asText("");
        if (area == null || tableId.isBlank()) return null;
        var siblings = allUnits.stream().filter(other -> tableId.equals(other.path("sourceTableId").asText("")))
                .filter(other -> { var a = nullableDecimal(other, "exclusiveArea"); return a != null && a.compareTo(area) == 0; }).toList();
        if (siblings.size() < 2) return null;
        var counts = siblings.stream().map(other -> nullableLong(other, "supplyCount"))
                .filter(java.util.Objects::nonNull).filter(count -> count > 0).distinct().toList();
        if (counts.size() != 1) return null;
        Matcher heading = Pattern.compile("형별\\s*공급계획").matcher(pageText);
        if (!heading.find()) return null;
        int end = pageText.indexOf('※', heading.start());
        String table = pageText.substring(heading.start(), end < 0 ? Math.min(pageText.length(), heading.start() + 5000) : end).replace(",", "");
        Matcher count = Pattern.compile("(?<![\\d.])" + counts.getFirst() + "(?![\\d.])").matcher(table);
        return count.find() && !count.find() ? counts.getFirst() : null;
    }

    record DormitoryFee(Long amount, int page, String evidence) { }

    static List<DetailedRentalCondition> verifiedConversionConditionsForBase(
            String housingType, Long baseDeposit, Long baseRent, Map<Integer, String> pages) {
        if (housingType == null || baseDeposit == null || baseRent == null || baseDeposit % 1000 != 0) return List.of();
        Pattern type = Pattern.compile("^\\s*" + Pattern.quote(housingType) + "(?:\\s|$).*");
        for (var entry : pages.entrySet()) {
            String page = entry.getValue();
            String header = page.replaceAll("\\s+", "");
            if (!header.contains("임대보증금(천원)") || !header.contains("최대전환시") || !header.contains("월임대료")) continue;
            String[] lines = page.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!type.matcher(line).matches()) continue;
                String normalized = line.replace(",", "");
                if (!Pattern.compile("(?<!\\d)" + (baseDeposit / 1000) + "(?!\\d)").matcher(normalized).find()
                        || !Pattern.compile("(?<!\\d)" + baseRent + "(?!\\d)").matcher(normalized).find()) continue;
                String surrounding = String.join("\n", java.util.Arrays.copyOfRange(lines,
                        Math.max(0, i - 1), Math.min(lines.length, i + 3)));
                return verifiedConversionConditions(housingType, Map.of(entry.getKey(),
                        "임대보증금(천원) 최대전환시 월임대료\n" + surrounding));
            }
        }
        return List.of();
    }

    static List<DetailedRentalCondition> verifiedConversionConditions(String housingType, Map<Integer, String> pages) {
        if (housingType == null || !housingType.matches("[0-9]{1,3}[A-Za-z](?:-[A-Za-z0-9]+)?")) return List.of();
        Pattern type = Pattern.compile("^\\s*" + Pattern.quote(housingType) + "(?:\\s|$).*");
        Pattern conversion = Pattern.compile("\\(([+-])\\)\\s*[0-9,]+\\s+([0-9,]+)\\s+([0-9,]+)");
        for (var entry : pages.entrySet()) {
            String page = entry.getValue(), header = page.replaceAll("\\s+", "");
            if (!header.contains("임대보증금(천원)") || !header.contains("최대전환시") || !header.contains("월임대료")) continue;
            String[] lines = page.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (!type.matcher(lines[i]).matches()) continue;
                List<DetailedRentalCondition> found = new ArrayList<>();
                for (int j = Math.max(0, i - 1); j <= Math.min(lines.length - 1, i + 2); j++) {
                    Matcher m = conversion.matcher(lines[j]);
                    if (!m.find()) continue;
                    if ((j < i && !"+".equals(m.group(1))) || (j > i && !"-".equals(m.group(1)))) continue;
                    try {
                        long deposit = Math.multiplyExact(Long.parseLong(m.group(2).replace(",", "")), 1000);
                        long rent = Long.parseLong(m.group(3).replace(",", ""));
                        boolean up = "+".equals(m.group(1));
                        found.add(new DetailedRentalCondition(up ? "MAX_CONVERSION" : "MIN_CONVERSION",
                                up ? "최대 보증금 전환" : "최소 보증금 전환", deposit, rent,
                                JSON.writeValueAsString(Map.of("page", entry.getKey(), "evidence", lines[j].trim()))));
                    } catch (ArithmeticException | NumberFormatException ignored) { return List.of(); }
                }
                if (found.size() == 2 && !found.get(0).conditionCode().equals(found.get(1).conditionCode())) return List.copyOf(found);
            }
        }
        return List.of();
    }

    static Long verifiedThousandWonDeposit(String evidence, Long deposit, Long rent, Map<Integer, String> pages) {
        if (evidence == null || deposit == null || rent == null || deposit > Long.MAX_VALUE / 1000) return null;
        Matcher type = Pattern.compile("(?<![A-Za-z0-9])(\\d{1,3}[A-Za-z](?:-[A-Za-z0-9]+)?)(?![A-Za-z0-9])").matcher(evidence);
        if (!type.find()) return null;
        String unitType = type.group(1);
        for (String page : pages.values()) {
            String compact = page.replaceAll("\\s+", "");
            if (!compact.contains("임대보증금(천원)") || !compact.contains("월임대료")) continue;
            for (String line : page.split("\\R")) {
                String clean = line.trim().replace(",", "");
                if (!clean.matches("^" + Pattern.quote(unitType) + "\\s+.*")) continue;
                String[] cells = clean.split("\\s+");
                if (cells.length >= 5 && cells[1].equals(deposit.toString()) && cells[4].equals(rent.toString())) {
                    return deposit * 1000;
                }
            }
        }
        return null;
    }

    static DormitoryFee verifiedDormitoryFee(String complexName, Map<Integer, String> pages) {
        if (complexName == null || complexName.isBlank()) return null;
        String name = complexName.replaceAll("\\s+", "");
        if (!name.contains("기숙사")) return null;
        for (var entry : pages.entrySet()) {
            String page = entry.getValue();
            if (!page.contains("기숙사비") || !page.contains("임대보증금") || !page.contains("임대료")) continue;
            for (String line : page.split("\\R")) {
                String compact = line.replaceAll("\\s+", "");
                if (!compact.startsWith(name)) continue;
                Matcher row = Pattern.compile("[-–—][-–—]([0-9,]+)원?").matcher(compact);
                if (row.find()) {
                    try { return new DormitoryFee(Long.parseLong(row.group(1).replace(",", "")), entry.getKey(), line.trim()); }
                    catch (NumberFormatException ignored) { return null; }
                }
            }
        }
        return null;
    }

    static boolean belongsToBuildingColumn(String pageText, String number, String evidence) {
        if (pageText == null || number == null || evidence == null || !number.matches("\\d{1,5}")) return false;
        if (!isBuildingOnlySupplyTable(pageText)) return false;
        Matcher heading = Pattern.compile("형별\\s*공급계획").matcher(pageText);
        if (!heading.find()) return false;
        int start = heading.start();
        int end = pageText.indexOf('※', start);
        String rawTable = pageText.substring(start, end < 0 ? Math.min(pageText.length(), start + 5000) : end);
        String table = rawTable.replaceAll("\\s+", "");
        if (!table.contains("해당동") || table.matches(".*(?:동호|호실|해당호|호번호|호별|주택호수|세대호수).*")) return false;
        // 예비입주자 유형별 표의 '공가 호수'는 개별 호실을 뜻하지 않습니다.
        if (!table.matches(".*공급.{0,100}형별.*") || !table.matches(".*금회모집.{0,100}예비자.*")) return false;
        Pattern exactNumber = Pattern.compile("(?<!\\d)" + Pattern.quote(number) + "(?!\\d)");
        return exactNumber.matcher(rawTable).find() && exactNumber.matcher(evidence).find();
    }

    static boolean isBuildingOnlySupplyTable(String pageText) {
        if (pageText == null) return false;
        Matcher heading = Pattern.compile("형별\\s*공급계획").matcher(pageText);
        if (!heading.find()) return false;
        int end = pageText.indexOf('※', heading.start());
        String table = pageText.substring(heading.start(), end < 0 ? Math.min(pageText.length(), heading.start() + 5000) : end).replaceAll("\\s+", "");
        return table.contains("해당동") && !table.matches(".*(?:동호|호실|해당호|호번호|호별|주택호수|세대호수).*")
                && table.matches(".*공급.{0,100}형별.*") && table.matches(".*금회모집.{0,100}예비자.*");
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
        documents.forEach(value -> {
            source.append('|').append(value.document().documentId())
                    .append(':').append(value.sha256())
                    .append(':').append(value.expectedUnitCount() == null ? "UNKNOWN" : value.expectedUnitCount());
            if (!value.relevantPages().equals(legacyRelevantPages(value.pageTexts()))) {
                source.append(":PAGES_V2:").append(new TreeSet<>(value.relevantPages()));
            }
        });
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
    private static Long nullableLong(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).asLong() : null;
    }
    private static BigDecimal nullableDecimal(JsonNode node, String field) {
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
    private record PageSlice(InspectedDocument document, Set<Integer> pages, byte[] preparedPdf, boolean textOnly) { }

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
        private final Map<String, Set<Integer>> pricingPagesByDocument = new LinkedHashMap<>();
        private boolean complete;
        private String nextCursor = "";

        void recordTextOnly(String documentId, int page) {
            String warning = "이미지 미분석: documentId=" + documentId + ", page=" + page + " (텍스트만 분석)";
            if (!warnings.contains(warning)) warnings.add(warning);
            ObjectNode attribute = JSON.createObjectNode();
            attribute.put("scope", "DOCUMENT");
            attribute.put("documentId", documentId);
            attribute.put("name", "이미지 미분석 페이지");
            attribute.put("value", Integer.toString(page));
            attribute.put("page", page);
            attribute.put("evidence", "크기 제한으로 PDF 추출 텍스트만 분석");
            additionalAttributes.put("DOCUMENT:" + documentId + ":이미지 미분석 페이지:" + page, attribute);
        }

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
                Set<Integer> pricingPages = new TreeSet<>();
                inspected.pageTexts().forEach((page, text) -> {
                    String compact = text == null ? "" : text.replaceAll("\\s+", "");
                    // 전환 예시 금액과 실제 주택별 임대조건은 다르다.
                    boolean separatelyAnnounced = Pattern.compile("주택별임대조건.{0,50}개별안내").matcher(compact).find();
                    if (!separatelyAnnounced && compact.contains("임대보증금")
                            && (compact.contains("월임대료") || compact.contains("월세"))
                            && text.lines().filter(line -> !line.contains("예시"))
                            .anyMatch(line -> Pattern.compile("(?<!\\d)(?:\\d{1,3}(?:,\\d{3})+|\\d{5,})(?!\\d)")
                                    .matcher(line).find())) {
                        pricingPages.add(page);
                    }
                });
                pricingPagesByDocument.put(inspected.document().documentId(), pricingPages);
            }
        }

        void accept(JsonNode root) { accept(root, false); }

        void accept(JsonNode root, boolean separatePages) {
            root.path("documents").forEach(value -> {
                String id = value.path("documentId").asText();
                JsonNode previous = documents.get(id);
                if (separatePages && previous != null && previous.path("expectedUnitCount").asInt() > value.path("expectedUnitCount").asInt()) {
                    ObjectNode merged = (ObjectNode) value.deepCopy();
                    merged.put("expectedUnitCount", previous.path("expectedUnitCount").asInt());
                    documents.put(id, merged);
                } else documents.put(id, value);
            });
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
                int expectedCount = Math.max(
                        trustedExpectedCounts.getOrDefault(documentId, 0),
                        documents.getOrDefault(documentId, normalized).path("expectedUnitCount").asInt(0));
                // 202호 같은 호수를 202번째 공급행으로 오인하지 않는다. 서버나 모델이 확인한
                // 전체 행 수 안에 들어오는 실제 연번일 때만 extractionIndex를 교정한다.
                if (sourceIndex != null && expectedCount > 0 && sourceIndex <= expectedCount) {
                    normalized.put("extractionIndex", sourceIndex);
                    normalized.put("unitId", documentId + "/item-" + sourceIndex);
                }
                String key = documentId + ":" + normalized.path("extractionIndex").asInt();
                JsonNode metadata = documents.get(documentId);
                if (separatePages && metadata != null && metadata.path("documentType").asText().contains("팸플릿")) {
                    String equivalent = units.entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith(documentId + ":"))
                            .filter(entry -> samePamphletType(entry.getValue(), normalized))
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    if (equivalent != null) {
                        JsonNode prior = units.get(equivalent);
                        normalized.put("extractionIndex", prior.path("extractionIndex").asInt());
                        normalized.put("unitId", prior.path("unitId").asText());
                        key = equivalent;
                    } else if (units.containsKey(key)) {
                        int next = indexes(documentId).stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
                        normalized.put("extractionIndex", next);
                        normalized.put("unitId", documentId + "/item-" + next);
                        key = documentId + ":" + next;
                    }
                }
                unitAliases.put(value.path("unitId").asText(), normalized.path("unitId").asText());
                units.put(key, normalized);
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
            root.path("warnings").forEach(value -> {
                if (!warnings.contains(value.asText())) warnings.add(value.asText());
            });
            complete = root.path("complete").asBoolean(false);
            nextCursor = root.path("nextCursor").asText("");
        }

        private boolean samePamphletType(JsonNode a, JsonNode b) {
            String typeA = a.path("housingType").asText("").replaceAll("\\s+|\\([^)]*\\)", "");
            String typeB = b.path("housingType").asText("").replaceAll("\\s+|\\([^)]*\\)", "");
            if (!typeA.matches("(?i)\\d{1,3}[a-z](?:-[a-z0-9]+)?") || !typeA.equalsIgnoreCase(typeB)) return false;
            String buildingA = a.path("buildingId").asText("");
            String buildingB = b.path("buildingId").asText("");
            if (!buildingA.equals(buildingB)) {
                JsonNode one = buildings.get(buildingA), two = buildings.get(buildingB);
                if (one == null || two == null) return false;
                String nameA = firstIdentity(one, "complexName"), nameB = firstIdentity(two, "complexName");
                String addressA = firstIdentity(one, "roadAddress", "lotAddress", "address");
                String addressB = firstIdentity(two, "roadAddress", "lotAddress", "address");
                if (nameA.isBlank() || !nameA.equals(nameB)
                        || (!addressA.isBlank() && !addressB.isBlank() && !addressA.equals(addressB))) return false;
            }
            String supplyA = a.path("supplyType").asText("").replaceAll("\\s+", "");
            String supplyB = b.path("supplyType").asText("").replaceAll("\\s+", "");
            if (!supplyA.equals(supplyB) && !supplyA.isBlank() && !supplyB.isBlank()
                    && !"행복주택".equals(supplyA) && !"행복주택".equals(supplyB)) return false;
            for (String field : List.of("buildingDong", "unitNumber")) {
                if (!a.path(field).asText("").replaceAll("\\s+", "")
                        .equals(b.path(field).asText("").replaceAll("\\s+", ""))) return false;
            }
            // 같은 페이지의 별도 행은 주택형이 같더라도 합치지 않습니다.
            return a.path("page").asInt() != b.path("page").asInt();
        }

        // 보정 응답의 순번은 다른 표의 순번일 수 있으므로 기존 행의 식별값을 확인한다.
        void acceptPricingRepair(JsonNode root) {
            Map<String, List<Integer>> missing = missingPricing();
            Map<String, JsonNode> matches = new LinkedHashMap<>();
            Map<String, String> aliases = new LinkedHashMap<>();
            for (JsonNode candidate : root.path("units")) {
                int index = candidate.path("extractionIndex").asInt();
                for (Map.Entry<String, List<Integer>> entry : missing.entrySet()) {
                    if (!entry.getValue().contains(index)) continue;
                    String key = entry.getKey() + ":" + index;
                    JsonNode previous = units.get(key);
                    long equivalentRows = units.entrySet().stream()
                            .filter(row -> row.getKey().startsWith(entry.getKey() + ":"))
                            .filter(row -> samePricingIdentity(row.getValue(), candidate)).count();
                    if (previous != null && equivalentRows == 1 && samePricingIdentity(previous, candidate)
                            && !matches.containsKey(key)) {
                        matches.put(key, candidate);
                        aliases.put(candidate.path("unitId").asText(), previous.path("unitId").asText());
                    }
                }
            }
            int expected = missing.values().stream().mapToInt(List::size).sum();
            if (matches.size() != expected || matches.values().stream().anyMatch(candidate ->
                    !hasConcreteFieldValue(candidate, "rentDeposit")
                            && !hasConcreteFieldValue(candidate, "monthlyRent")
                            && !hasPricingCondition(root, candidate.path("unitId").asText()))) {
                warnings.add("임대금액 보정 행을 기존 주택과 확실히 연결하지 못해 적용하지 않았습니다.");
                return;
            }
            matches.forEach((key, candidate) -> {
                ObjectNode previous = (ObjectNode) units.get(key).deepCopy();
                for (String field : List.of("rentDeposit", "monthlyRent")) {
                    if (hasConcreteFieldValue(candidate, field)) previous.set(field, candidate.get(field));
                }
                units.put(key, previous);
            });
            root.path("rentalConditions").forEach(condition -> {
                String mapped = aliases.get(condition.path("unitId").asText());
                if (mapped == null) return;
                ObjectNode normalized = (ObjectNode) condition.deepCopy();
                normalized.put("unitId", mapped);
                rentalConditions.put(rentalConditionKey(normalized), normalized);
            });
        }

        private boolean hasPricingCondition(JsonNode root, String unitId) {
            for (JsonNode condition : root.path("rentalConditions")) {
                if (unitId.equals(condition.path("unitId").asText())
                        && (hasConcreteFieldValue(condition, "rentDeposit")
                        || hasConcreteFieldValue(condition, "monthlyRent"))) return true;
            }
            return false;
        }

        private boolean samePricingIdentity(JsonNode previous, JsonNode candidate) {
            boolean identified = false;
            String oldLabel = previous.path("sourceRowLabel").asText("").replaceAll("\\s+", "");
            String newLabel = candidate.path("sourceRowLabel").asText("").replaceAll("\\s+", "");
            if (!oldLabel.isBlank() && !newLabel.isBlank()) {
                if (!oldLabel.equals(newLabel)) return false;
            }
            for (String field : List.of("unitNumber", "buildingDong", "housingType", "supplyType", "exclusiveArea")) {
                String oldValue = previous.path(field).asText("").replaceAll("\\s+", "");
                String newValue = candidate.path(field).asText("").replaceAll("\\s+", "");
                if (oldValue.isBlank() || "null".equals(oldValue)) continue;
                if (newValue.isBlank() || "null".equals(newValue) || !oldValue.equals(newValue)) return false;
                identified = true;
            }
            return identified;
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
            if (!isPlausibleUnitNumber(unitNumber) && isPlausibleUnitNumber(buildingDong)
                    && buildingDong.trim().endsWith("호")) {
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
            // DB 반영을 막는 기준은 공급행 누락과 공급표 페이지 누락으로 한정한다.
            // 추가정보나 선택 열의 일부 누락은 경고로 남기되 정상 행 전체를 폐기하지 않는다.
            return rowsComplete && missingSourcePages().isEmpty() && missingPricing().isEmpty();
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
            Map<String, List<Integer>> pricing = missingPricing();
            if (!pricing.isEmpty()) {
                return "다음 문서에는 원문 임대보증금·월임대료 표가 있지만 반환된 주택에 금액이 없습니다. "
                        + "금액 표와 공급형을 직접 확인해 이미 추출한 각 행의 extractionIndex를 유지하고 "
                        + "아래 기존 행의 호수, 주택형, 전용면적 등 식별값을 빠짐없이 그대로 반환하세요. "
                        + "해당하는 원문 보증금과 월임대료, 대상별 rentalConditions를 반환하세요. "
                        + "천원 단위는 원으로 변환하고 원문에 없는 값은 만들지 마세요.\n"
                        + pricing.entrySet().stream().map(entry -> "documentId=" + entry.getKey()
                        + ", missingPricingIndexes=" + entry.getValue() + ", 기존 행="
                        + entry.getValue().stream().map(index -> units.get(entry.getKey() + ":" + index))
                        .filter(java.util.Objects::nonNull).map(JsonNode::toString)
                        .collect(java.util.stream.Collectors.joining("; ")))
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            if (!complete && !nextCursor.isBlank()) {
                return "AI nextCursor=" + nextCursor + " 바로 다음 원문 행부터 반환하세요.";
            }
            return "";
        }

        long progressScore() {
            long populatedFields = units.values().stream()
                    .mapToLong(unit -> CONSISTENT_UNIT_FIELDS.stream()
                            .filter(field -> hasFieldValue(unit, field)).count())
                    .sum();
            return units.size() * 10_000L
                    + rentalConditions.size() * 100L
                    + additionalAttributes.size() * 10L
                    + populatedFields;
        }

        private Map<String, List<Integer>> missingPricing() {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            pricingPagesByDocument.forEach((documentId, pages) -> {
                if (pages.isEmpty()) return;
                String prefix = documentId + ":";
                List<JsonNode> documentUnits = units.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(prefix)).map(Map.Entry::getValue).toList();
                if (documentUnits.isEmpty()) return;
                boolean hasPrice = documentUnits.stream().anyMatch(unit ->
                        hasConcreteFieldValue(unit, "rentDeposit") || hasConcreteFieldValue(unit, "monthlyRent")
                        || rentalConditions.values().stream().anyMatch(condition ->
                                unit.path("unitId").asText().equals(condition.path("unitId").asText())
                                        && (hasConcreteFieldValue(condition, "rentDeposit")
                                        || hasConcreteFieldValue(condition, "monthlyRent"))));
                if (!hasPrice) result.put(documentId, documentUnits.stream()
                        .map(unit -> unit.path("extractionIndex").asInt()).filter(index -> index > 0).sorted().toList());
            });
            return result;
        }

        int maximumExpectedCount() {
            return expectedCounts().values().stream().mapToInt(Integer::intValue).max().orElse(0);
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
            // 기존 파서의 단일 주택 힌트만으로는 문서의 모든 '1'이 표 행임을 증명할 수 없다.
            // 단일 주택 공고의 신청 안내 번호를 누락 페이지로 오인하지 않는다.
            if (expected <= 1) return Set.of();
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
            if (missingPricing().containsKey(documentId)) {
                for (int page : pricingPagesByDocument.getOrDefault(documentId, Set.of())) {
                    addPageWithNeighbors(pages, page, inspected.pages());
                }
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
