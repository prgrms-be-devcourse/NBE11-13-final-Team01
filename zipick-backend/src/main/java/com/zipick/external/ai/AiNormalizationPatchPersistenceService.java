package com.zipick.external.ai;

import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.NoticeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 근거 검증을 통과한 3.8 Flash 수정값을 이미 저장된 공고 데이터에 반영합니다. */
@Service
public class AiNormalizationPatchPersistenceService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NoticeRepository noticeRepository;
    private final HousingRepository housingRepository;
    private final HousingSupplyRepository supplyRepository;

    public AiNormalizationPatchPersistenceService(
            NoticeRepository noticeRepository,
            HousingRepository housingRepository,
            HousingSupplyRepository supplyRepository
    ) {
        this.noticeRepository = noticeRepository;
        this.housingRepository = housingRepository;
        this.supplyRepository = supplyRepository;
    }

    @Transactional
    public void apply(
            Map<String, AiNormalizationJob> jobsByDocumentId,
            List<GeminiNormalizationCorrection> corrections
    ) {
        for (GeminiNormalizationCorrection correction : corrections) {
            AiNormalizationJob job = jobsByDocumentId.get(correction.documentId());
            if (job == null) throw new IllegalStateException("정규화 작업을 찾을 수 없습니다: " + correction.documentId());
            Notice notice = noticeRepository.findBySourceAndExternalNoticeId(
                            job.getSource(), externalNoticeId(job))
                    .orElseThrow(() -> new IllegalStateException(
                            "정규화 대상 공고를 찾을 수 없습니다: " + correction.documentId()));
            switch (correction.target()) {
                case "DOCUMENT" -> applyDocument(notice, correction);
                case "HOUSING" -> applyHousing(notice, job, correction);
                case "SUPPLY", "ATTRIBUTE" -> applySupply(notice, job, correction);
                default -> throw new IllegalStateException("지원하지 않는 정규화 대상입니다: " + correction.target());
            }
        }
    }

    private void applyDocument(Notice notice, GeminiNormalizationCorrection correction) {
        LocalDate winner = null;
        Instant start = null;
        Instant end = null;
        switch (correction.field()) {
            case "winnerAnnouncementDate" -> winner = parseDate(correction.stringValue());
            case "applicationStartDate" -> start = parseInstant(correction.stringValue());
            case "applicationEndDate" -> end = parseInstant(correction.stringValue());
            default -> throw new IllegalStateException("허용되지 않은 공고 필드입니다: " + correction.field());
        }
        notice.applyAiNormalizedDates(winner, start, end);
    }

    private void applyHousing(Notice notice, AiNormalizationJob job, GeminiNormalizationCorrection correction) {
        Housing housing = findHousing(notice, job, correction.sourceHousingKey());
        String name = null;
        String province = null;
        String district = null;
        String address = null;
        String lot = null;
        String road = null;
        switch (correction.field()) {
            case "complexName" -> name = correction.stringValue();
            case "provinceName" -> province = correction.stringValue();
            case "districtName" -> district = correction.stringValue();
            case "fullAddress" -> address = correction.stringValue();
            case "lotAddress" -> lot = correction.stringValue();
            case "roadAddress" -> road = correction.stringValue();
            default -> throw new IllegalStateException("허용되지 않은 주택 필드입니다: " + correction.field());
        }
        housing.applyAiNormalization(name, province, district, address, lot, road);
    }

    private void applySupply(Notice notice, AiNormalizationJob job, GeminiNormalizationCorrection correction) {
        HousingSupply supply = findSupply(notice, job, correction);
        if ("supplyCount".equals(correction.field())
                && com.zipick.notice.service.NoticeSyncPersistenceService.hasVerifiedSharedSupplyCount(supply.getRawPayload())) return;
        String housingType = null;
        String supplyType = null;
        String dong = null;
        String unit = null;
        Integer floor = null;
        Integer rooms = null;
        BigDecimal area = null;
        Long deposit = null;
        Long rent = null;
        Integer count = null;
        String status = null;
        Boolean elevator = null;
        Boolean community = null;
        Integer parking = null;
        Boolean mechanical = null;
        Integer mechanicalSpaces = null;
        String rawPayload = null;
        if ("ATTRIBUTE".equals(correction.target())) {
            rawPayload = appendAttribute(supply.getRawPayload(), correction);
        } else {
            switch (correction.field()) {
                case "houseTypeName" -> housingType = correction.stringValue();
                case "supplyTypeName" -> supplyType = correction.stringValue();
                case "buildingDong" -> dong = correction.stringValue();
                case "unitNumber" -> unit = correction.stringValue();
                case "floor" -> floor = safeInteger(correction.integerValue());
                case "roomCount" -> rooms = safeInteger(correction.integerValue());
                case "exclusiveArea" -> area = safeDecimal(correction.decimalValue());
                case "rentDeposit" -> deposit = nonNegative(correction.integerValue());
                case "monthlyRent" -> rent = nonNegative(correction.integerValue());
                case "supplyCount" -> count = safeInteger(correction.integerValue());
                case "supplyStatus" -> status = correction.stringValue();
                case "elevatorAvailable" -> elevator = correction.booleanValue();
                case "communityFacilityAvailable" -> community = correction.booleanValue();
                case "totalParkingSpaces" -> parking = safeInteger(correction.integerValue());
                case "mechanicalParkingAvailable" -> mechanical = correction.booleanValue();
                case "mechanicalParkingSpaces" -> mechanicalSpaces = safeInteger(correction.integerValue());
                default -> throw new IllegalStateException("허용되지 않은 공급 필드입니다: " + correction.field());
            }
        }
        supply.applyAiNormalization(housingType, supplyType, dong, unit, floor, rooms, area,
                deposit, rent, count, status, elevator, community, parking, mechanical,
                mechanicalSpaces, rawPayload);
    }

    private HousingSupply findSupply(Notice notice, AiNormalizationJob job, GeminiNormalizationCorrection correction) {
        if (correction.sourceHousingKey() != null && !correction.sourceHousingKey().isBlank()) {
            var housing = housingRepository.findByNoticeIdAndSourceHousingKey(notice.getId(), correction.sourceHousingKey());
            if (housing.isPresent() && housing.get().isActive()) {
                var direct = supplyRepository.findByHousingIdAndSourceSupplyKey(housing.get().getId(), correction.sourceSupplyKey());
                if (direct.isPresent() && direct.get().isActive()) return direct.get();
            }
        }
        List<HousingSupply> matches = supplyRepository.findAllByHousingNoticeIdAndSourceSupplyKey(
                notice.getId(), correction.sourceSupplyKey()).stream()
                .filter(HousingSupply::isActive).filter(value -> value.getHousing().isActive()).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("공급정보 키가 없거나 중복됩니다: " + correction.sourceSupplyKey());
        }
        return matches.getFirst();
    }

    private Housing findHousing(Notice notice, AiNormalizationJob job, String sourceKey) {
        var direct = housingRepository.findByNoticeIdAndSourceHousingKey(notice.getId(), sourceKey);
        if (direct.isPresent()) return direct.get();
        // 수집 시 동일 주소로 병합되면 DB에는 기존 키가 남으므로 당시 입력 주소로만 대응한다.
        var addresses = new java.util.HashSet<String>();
        var input = JSON.readTree(job.getInputPayload());
        for (var row : input.path("databaseCandidate").path("rows")) {
            if (!java.util.Objects.equals(sourceKey, row.path("sourceHousingKey").asText())) continue;
            for (String field : List.of("fullAddress", "lotAddress", "roadAddress")) {
                String address = normalizedAddress(row.path(field).asText(""));
                if (!address.isBlank()) addresses.add(address);
            }
        }
        if (!addresses.isEmpty()) {
            List<Housing> matches = housingRepository.findAllByNoticeId(notice.getId()).stream()
                    .filter(housing -> java.util.stream.Stream.of(
                                    housing.getAddress(), housing.getLotAddress(), housing.getRoadAddress())
                            .map(this::normalizedAddress).anyMatch(addresses::contains))
                    .toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw new IllegalStateException("정규화 대상 주택을 찾을 수 없습니다: " + sourceKey);
    }

    private String normalizedAddress(String address) {
        return address == null ? "" : address.replaceAll("\\s+", "").trim();
    }

    @SuppressWarnings("unchecked")
    private String appendAttribute(String rawPayload, GeminiNormalizationCorrection correction) {
        Map<String, Object> root = rawPayload == null || rawPayload.isBlank()
                ? new LinkedHashMap<>() : JSON.readValue(rawPayload, Map.class);
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
    }

    private String externalNoticeId(AiNormalizationJob job) {
        String prefix = job.getSource() + ":";
        return job.getDocumentId().startsWith(prefix)
                ? job.getDocumentId().substring(prefix.length()) : job.getDocumentId();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDate.parse(value.trim().substring(0, 10));
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException ignored) {
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed).atStartOfDay(KOREA_ZONE).toInstant();
            }
            return LocalDateTime.parse(trimmed, DATE_TIME).atZone(KOREA_ZONE).toInstant();
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
}
