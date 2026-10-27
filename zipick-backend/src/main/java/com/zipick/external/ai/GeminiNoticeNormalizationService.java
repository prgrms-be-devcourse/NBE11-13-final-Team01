package com.zipick.external.ai;

import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.service.NoticeSyncCommand;
import com.zipick.notice.repository.NoticeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 수집을 모두 마친 뒤 Lite 결과를 묶어서 3.8 Flash로 검수하고 DB 저장 후보에 패치를 적용합니다. */
@Service
public class GeminiNoticeNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(GeminiNoticeNormalizationService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GeminiDbNormalizationReviewer reviewer;
    private final AiNormalizationJobService jobService;
    private final AiNormalizationPatchPersistenceService patchPersistenceService;
    private final NoticeRepository noticeRepository;

    @Autowired
    public GeminiNoticeNormalizationService(
            GeminiDbNormalizationReviewer reviewer,
            AiNormalizationJobService jobService,
            AiNormalizationPatchPersistenceService patchPersistenceService,
            NoticeRepository noticeRepository
    ) {
        this.reviewer = reviewer;
        this.jobService = jobService;
        this.patchPersistenceService = patchPersistenceService;
        this.noticeRepository = noticeRepository;
    }

    public GeminiNoticeNormalizationService(
            GeminiDbNormalizationReviewer reviewer,
            AiNormalizationJobService jobService,
            AiNormalizationPatchPersistenceService patchPersistenceService
    ) {
        this(reviewer, jobService, patchPersistenceService, null);
    }

    /** 이번 수집에서 Lite 추출이 끝난 문서를 정규화 대기열에 등록합니다. */
    public void enqueue(List<NoticeSyncCommand> commands) {
        if (!reviewer.enabled() || commands == null || commands.isEmpty()) return;
        List<GeminiNormalizationDocument> documents = commands.stream()
                .filter(command -> noticeRepository == null || noticeRepository
                        .findBySourceAndExternalNoticeId(
                                command.notice().source(), command.notice().noticeId())
                        .isPresent())
                .filter(command -> hasLiteResult(command.notice()))
                .map(this::toDocument)
                .toList();
        if (!documents.isEmpty()) jobService.enqueue(documents);
    }

    /** 신규 수집과 무관하게 PENDING/RETRY 작업을 읽어 정규화만 다시 수행합니다. */
    public void normalizePending() {
        if (!reviewer.enabled()) return;
        List<AiNormalizationJob> dueJobs = jobService.findDue();
        if (dueJobs.isEmpty()) return;

        Map<String, AiNormalizationJob> jobsByDocumentId = new LinkedHashMap<>();
        List<GeminiNormalizationDocument> documents = new ArrayList<>();
        for (AiNormalizationJob job : dueJobs) {
            try {
                GeminiNormalizationDocument document = jobService.readDocument(job);
                if (!isCurrentLiteDocument(document)) {
                    jobService.markSuccess(List.of(job.getId()));
                    log.info("현재 추출 버전이 아닌 AI 정규화 작업을 종료합니다: document={}",
                            document.documentId());
                    continue;
                }
                if (noticeRepository != null && noticeRepository
                        .findBySourceAndExternalNoticeId(document.agency(), externalNoticeId(document))
                        .isEmpty()) {
                    jobService.markSuccess(List.of(job.getId()));
                    log.info("저장되지 않은 공고의 AI 정규화 작업을 종료합니다: document={}",
                            document.documentId());
                    continue;
                }
                documents.add(document);
                jobsByDocumentId.put(document.documentId(), job);
            } catch (RuntimeException exception) {
                jobService.markRetry(List.of(job.getId()), "저장된 정규화 입력을 읽지 못했습니다: " + exception.getMessage());
            }
        }
        if (documents.isEmpty()) return;

        try {
            GeminiNormalizationResult result = reviewer.review(documents);
            Set<String> deferred = Set.copyOf(result.deferredDocumentIds());
            List<Long> deferredIds = jobsByDocumentId.entrySet().stream()
                    .filter(entry -> deferred.contains(entry.getKey()))
                    .map(entry -> entry.getValue().getId())
                    .toList();
            if (!deferredIds.isEmpty()) {
                jobService.markRetry(deferredIds, String.join(" | ", result.warnings()));
            }

            List<Long> completedIds = jobsByDocumentId.entrySet().stream()
                    .filter(entry -> !deferred.contains(entry.getKey()))
                    .map(entry -> entry.getValue().getId())
                    .toList();
            int successCount = 0;
            int patchRetryCount = 0;
            if (!completedIds.isEmpty()) {
                Map<String, List<GeminiNormalizationCorrection>> correctionsByDocument = result.corrections()
                        .stream().collect(Collectors.groupingBy(
                                GeminiNormalizationCorrection::documentId,
                                LinkedHashMap::new, Collectors.toList()));
                for (Map.Entry<String, AiNormalizationJob> entry : jobsByDocumentId.entrySet()) {
                    if (deferred.contains(entry.getKey())) continue;
                    AiNormalizationJob job = entry.getValue();
                    try {
                        patchPersistenceService.apply(
                                Map.of(entry.getKey(), job),
                                correctionsByDocument.getOrDefault(entry.getKey(), List.of()));
                        jobService.markSuccess(List.of(job.getId()));
                        successCount++;
                    } catch (RuntimeException exception) {
                        jobService.markRetry(List.of(job.getId()),
                                "정규화 DB 반영 실패: " + exception.getMessage());
                        patchRetryCount++;
                        log.warn("AI 정규화 결과의 DB 반영을 다음 실행으로 연기합니다: document={}, reason={}",
                                entry.getKey(), exception.getMessage());
                    }
                }
            }
            log.info("AI 정규화 대기열 처리: 대상={}, 성공={}, 재시도={}, 실제호출={}, 캐시={}",
                    documents.size(), successCount, deferredIds.size() + patchRetryCount,
                    result.executedCalls(), result.cacheHits());
            result.warnings().forEach(warning -> log.warn("AI 2단계 정규화: {}", warning));
        } catch (RuntimeException exception) {
            jobService.markRetry(jobsByDocumentId.values().stream().map(AiNormalizationJob::getId).toList(),
                    "정규화 처리 실패: " + exception.getMessage());
            log.warn("AI 정규화 대기열 전체를 다음 실행으로 연기합니다: {}", exception.getMessage());
        }
    }

    private String externalNoticeId(GeminiNormalizationDocument document) {
        String prefix = document.agency() + ":";
        return document.documentId().startsWith(prefix)
                ? document.documentId().substring(prefix.length())
                : document.documentId();
    }

    public List<NoticeSyncCommand> normalize(List<NoticeSyncCommand> commands) {
        if (!reviewer.enabled() || commands == null || commands.isEmpty()) return List.copyOf(commands);
        List<GeminiNormalizationDocument> documents = commands.stream()
                .filter(command -> hasLiteResult(command.notice()))
                .map(this::toDocument)
                .toList();
        if (documents.isEmpty()) return List.copyOf(commands);

        GeminiNormalizationResult result = reviewer.review(documents);
        Map<String, List<GeminiNormalizationCorrection>> byDocument = new LinkedHashMap<>();
        result.corrections().forEach(correction -> byDocument
                .computeIfAbsent(correction.documentId(), ignored -> new ArrayList<>()).add(correction));
        List<NoticeSyncCommand> normalized = commands.stream().map(command -> {
            List<GeminiNormalizationCorrection> corrections = byDocument.get(
                    normalizationDocumentId(command.notice()));
            if (corrections == null) corrections = byDocument.get(command.notice().noticeId());
            return corrections == null || corrections.isEmpty()
                    ? command
                    : new NoticeSyncCommand(apply(command.notice(), corrections), command.rawPayload());
        }).toList();
        log.info("AI 2단계 정규화 적용: 대상문서={}, 계획호출={}, 실제호출={}, 캐시={}, 수정={}, 연기={}",
                documents.size(), result.plannedCalls(), result.executedCalls(), result.cacheHits(),
                result.corrections().size(), result.deferredDocumentIds().size());
        result.warnings().forEach(warning -> log.warn("AI 2단계 정규화: {}", warning));
        return normalized;
    }

    private boolean hasLiteResult(NoticeResponse notice) {
        return notice.houses().stream().flatMap(housing -> housing.supplies().stream())
                .map(NoticeSupplyResponse::rawPayload)
                .anyMatch(this::isLitePayload);
    }

    private boolean isLitePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) return false;
        try {
            return GeminiPdfHousingExtractor.PROMPT_VERSION.equals(
                    JSON.readTree(rawPayload).path("parser").asText(""));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isCurrentLiteDocument(GeminiNormalizationDocument document) {
        Object rows = document.liteExtraction().get("rows");
        if (!(rows instanceof List<?> list) || list.isEmpty()) return false;
        return list.stream().filter(Map.class::isInstance).map(Map.class::cast).allMatch(row -> {
            Object raw = row.get("rawPayload");
            if (!(raw instanceof Map<?, ?> rawMap)) return false;
            return GeminiPdfHousingExtractor.PROMPT_VERSION.equals(String.valueOf(rawMap.get("parser")));
        });
    }

    private GeminiNormalizationDocument toDocument(NoticeSyncCommand command) {
        NoticeResponse notice = command.notice();
        List<Map<String, Object>> liteRows = new ArrayList<>();
        List<Map<String, Object>> candidateRows = new ArrayList<>();
        for (NoticeHousingResponse housing : notice.houses()) {
            for (NoticeSupplyResponse supply : housing.supplies()) {
                if (!isLitePayload(supply.rawPayload())) continue;
                Map<String, Object> liteRow = new LinkedHashMap<>();
                put(liteRow, "sourceHousingKey", housing.sourceHousingKey());
                put(liteRow, "sourceSupplyKey", supply.sourceSupplyKey());
                liteRow.put("rawPayload", JSON.readValue(supply.rawPayload(), Object.class));
                liteRows.add(liteRow);
                candidateRows.add(candidateRow(housing, supply));
            }
        }
        return new GeminiNormalizationDocument(
                normalizationDocumentId(notice), notice.source(), notice.title(),
                Map.of("rows", liteRows),
                Map.of(
                        "notice", compactNotice(notice),
                        "rows", candidateRows
                )
        );
    }

    private String normalizationDocumentId(NoticeResponse notice) {
        return notice.source() + ":" + notice.noticeId();
    }

    private Map<String, Object> compactNotice(NoticeResponse notice) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "noticeId", notice.noticeId());
        put(result, "title", notice.title());
        put(result, "applicationStartDate", notice.applicationStartDate());
        put(result, "applicationEndDate", notice.applicationEndDate());
        put(result, "winnerAnnouncementDate", notice.winnerAnnouncementDate());
        return result;
    }

    private Map<String, Object> candidateRow(NoticeHousingResponse housing, NoticeSupplyResponse supply) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "sourceHousingKey", housing.sourceHousingKey());
        put(result, "sourceSupplyKey", supply.sourceSupplyKey());
        put(result, "complexName", housing.complexName());
        put(result, "provinceName", housing.provinceName());
        put(result, "districtName", housing.districtName());
        put(result, "fullAddress", housing.fullAddress());
        put(result, "lotAddress", housing.lotAddress());
        put(result, "roadAddress", housing.roadAddress());
        put(result, "houseTypeName", supply.houseTypeName());
        put(result, "supplyTypeName", supply.supplyTypeName());
        put(result, "buildingDong", supply.buildingDong());
        put(result, "unitNumber", supply.unitNumber());
        put(result, "floor", supply.floor());
        put(result, "roomCount", supply.roomCount());
        put(result, "exclusiveArea", supply.exclusiveArea());
        put(result, "rentDeposit", supply.rentDeposit());
        put(result, "monthlyRent", supply.monthlyRent());
        put(result, "supplyCount", supply.supplyCount());
        put(result, "supplyStatus", supply.supplyStatus());
        put(result, "elevatorAvailable", supply.elevatorAvailable());
        put(result, "communityFacilityAvailable", supply.communityFacilityAvailable());
        put(result, "totalParkingSpaces", supply.totalParkingSpaces());
        put(result, "mechanicalParkingAvailable", supply.mechanicalParkingAvailable());
        put(result, "mechanicalParkingSpaces", supply.mechanicalParkingSpaces());
        return result;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) target.put(key, value);
    }

    private NoticeResponse apply(NoticeResponse notice, List<GeminiNormalizationCorrection> corrections) {
        NoticeResponse current = notice;
        for (GeminiNormalizationCorrection correction : corrections) {
            current = switch (correction.target()) {
                case "DOCUMENT" -> applyDocument(current, correction);
                case "HOUSING", "SUPPLY", "ATTRIBUTE" -> applyRows(current, correction);
                default -> current;
            };
        }
        return current;
    }

    private NoticeResponse applyDocument(NoticeResponse notice, GeminiNormalizationCorrection correction) {
        String start = notice.applicationStartDate();
        String end = notice.applicationEndDate();
        String winner = notice.winnerAnnouncementDate();
        if ("applicationStartDate".equals(correction.field())) start = replacement(correction.stringValue(), start);
        if ("applicationEndDate".equals(correction.field())) end = replacement(correction.stringValue(), end);
        if ("winnerAnnouncementDate".equals(correction.field())) winner = replacement(correction.stringValue(), winner);
        return new NoticeResponse(notice.source(), notice.noticeId(), notice.statusName(), notice.title(),
                notice.supplyInstitutionName(), notice.previousNoticeId(), notice.announcementDate(), winner,
                start, end, notice.reference(), notice.url(), notice.pcUrl(), notice.mobileUrl(),
                notice.houses(), notice.attachments());
    }

    private NoticeResponse applyRows(NoticeResponse notice, GeminiNormalizationCorrection correction) {
        List<NoticeHousingResponse> housings = notice.houses().stream()
                .map(housing -> applyHousing(housing, correction))
                .toList();
        return new NoticeResponse(notice.source(), notice.noticeId(), notice.statusName(), notice.title(),
                notice.supplyInstitutionName(), notice.previousNoticeId(), notice.announcementDate(),
                notice.winnerAnnouncementDate(), notice.applicationStartDate(), notice.applicationEndDate(),
                notice.reference(), notice.url(), notice.pcUrl(), notice.mobileUrl(), housings, notice.attachments());
    }

    private NoticeHousingResponse applyHousing(
            NoticeHousingResponse housing, GeminiNormalizationCorrection correction
    ) {
        boolean selected = correction.sourceHousingKey() == null
                || correction.sourceHousingKey().equals(housing.sourceHousingKey());
        String complexName = housing.complexName();
        String province = housing.provinceName();
        String district = housing.districtName();
        String fullAddress = housing.fullAddress();
        String lotAddress = housing.lotAddress();
        String roadAddress = housing.roadAddress();
        if (selected && "HOUSING".equals(correction.target())) {
            switch (correction.field()) {
                case "complexName" -> complexName = replacement(correction.stringValue(), complexName);
                case "provinceName" -> province = replacement(correction.stringValue(), province);
                case "districtName" -> district = replacement(correction.stringValue(), district);
                case "fullAddress" -> fullAddress = replacement(correction.stringValue(), fullAddress);
                case "lotAddress" -> lotAddress = replacement(correction.stringValue(), lotAddress);
                case "roadAddress" -> roadAddress = replacement(correction.stringValue(), roadAddress);
                default -> { }
            }
        }
        List<NoticeSupplyResponse> supplies = housing.supplies().stream()
                .map(supply -> applySupply(supply, correction))
                .toList();
        return new NoticeHousingResponse(housing.sourceHousingKey(), complexName, province, district,
                fullAddress, lotAddress, roadAddress, housing.roadName(), housing.legalDongName(), housing.pnu(),
                housing.heatingMethodName(), housing.totalHouseholdCount(), supplies, housing.media());
    }

    private NoticeSupplyResponse applySupply(
            NoticeSupplyResponse supply, GeminiNormalizationCorrection correction
    ) {
        if (correction.sourceSupplyKey() != null
                && !correction.sourceSupplyKey().equals(supply.sourceSupplyKey())) return supply;
        String houseType = supply.houseTypeName();
        String supplyType = supply.supplyTypeName();
        String dong = supply.buildingDong();
        String unit = supply.unitNumber();
        Integer floor = supply.floor();
        Integer rooms = supply.roomCount();
        BigDecimal area = supply.exclusiveArea();
        Long deposit = supply.rentDeposit();
        Long rent = supply.monthlyRent();
        Long count = supply.supplyCount();
        String status = supply.supplyStatus();
        Boolean elevator = supply.elevatorAvailable();
        Boolean community = supply.communityFacilityAvailable();
        Integer parking = supply.totalParkingSpaces();
        Boolean mechanical = supply.mechanicalParkingAvailable();
        Integer mechanicalSpaces = supply.mechanicalParkingSpaces();
        String raw = supply.rawPayload();
        if ("SUPPLY".equals(correction.target())) {
            switch (correction.field()) {
                case "houseTypeName" -> houseType = replacement(correction.stringValue(), houseType);
                case "supplyTypeName" -> supplyType = replacement(correction.stringValue(), supplyType);
                case "buildingDong" -> dong = replacement(correction.stringValue(), dong);
                case "unitNumber" -> unit = replacement(correction.stringValue(), unit);
                case "floor" -> floor = replacement(safeInteger(correction.integerValue()), floor);
                case "roomCount" -> rooms = replacement(safeInteger(correction.integerValue()), rooms);
                case "exclusiveArea" -> area = replacement(safeDecimal(correction.decimalValue()), area);
                case "rentDeposit" -> deposit = replacement(nonNegative(correction.integerValue()), deposit);
                case "monthlyRent" -> rent = replacement(nonNegative(correction.integerValue()), rent);
                case "supplyCount" -> count = replacement(nonNegative(correction.integerValue()), count);
                case "supplyStatus" -> status = replacement(correction.stringValue(), status);
                case "elevatorAvailable" -> elevator = replacement(correction.booleanValue(), elevator);
                case "communityFacilityAvailable" -> community = replacement(correction.booleanValue(), community);
                case "totalParkingSpaces" -> parking = replacement(safeInteger(correction.integerValue()), parking);
                case "mechanicalParkingAvailable" -> mechanical = replacement(correction.booleanValue(), mechanical);
                case "mechanicalParkingSpaces" -> mechanicalSpaces = replacement(
                        safeInteger(correction.integerValue()), mechanicalSpaces);
                default -> { }
            }
        } else if ("ATTRIBUTE".equals(correction.target())) {
            raw = appendAttribute(raw, correction);
        }
        return new NoticeSupplyResponse(supply.sourceSupplyKey(), supply.externalHouseId(), houseType, supplyType,
                dong, unit, floor, rooms, area, count, supply.supplyHouseCount(), deposit,
                supply.contractDeposit(), supply.interimPayment(), supply.balance(), rent, raw,
                supply.sourceRowNumber(), status, supply.nearbySubwayStation(), supply.subwayWalkDistanceKm(),
                elevator, community, parking, mechanical, mechanicalSpaces, supply.rentalConditions());
    }

    @SuppressWarnings("unchecked")
    private String appendAttribute(String rawPayload, GeminiNormalizationCorrection correction) {
        try {
            Map<String, Object> root = rawPayload == null || rawPayload.isBlank()
                    ? new LinkedHashMap<>()
                    : JSON.readValue(rawPayload, Map.class);
            List<Object> attributes = new ArrayList<>((List<Object>) root.getOrDefault(
                    "unitAdditionalAttributes", List.of()));
            boolean exists = attributes.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .anyMatch(item -> correction.attributeName().equals(String.valueOf(item.get("name")))
                            && correction.attributeValue().equals(String.valueOf(item.get("value"))));
            if (!exists) attributes.add(Map.of(
                    "name", correction.attributeName(), "value", correction.attributeValue(),
                    "scope", "UNIT", "evidence", correction.evidence(), "reviewedBy", "gemini-3.8-flash"
            ));
            root.put("unitAdditionalAttributes", attributes);
            root.put("normalizationReviewer", GeminiDbNormalizationReviewer.PROMPT_VERSION);
            return JSON.writeValueAsString(root);
        } catch (RuntimeException exception) {
            log.warn("추가 속성 패치를 적용하지 못했습니다: supplyKey={}, reason={}",
                    correction.sourceSupplyKey(), exception.getMessage());
            return rawPayload;
        }
    }

    private Integer safeInteger(Long value) {
        return value == null || value < 0 || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    private Long nonNegative(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private BigDecimal safeDecimal(String value) {
        try {
            BigDecimal parsed = value == null ? null : new BigDecimal(value);
            return parsed != null && parsed.signum() >= 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String replacement(String value, String current) {
        return value == null || value.isBlank() ? current : value;
    }

    private <T> T replacement(T value, T current) {
        return value == null ? current : value;
    }
}
