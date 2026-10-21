package com.zipick.notice.service;

import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.HousingMedia;
import com.zipick.notice.domain.HousingSupplyCondition;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeAttachment;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.domain.NoticeStatusReason;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.repository.AgencyRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.HousingSupplyConditionRepository;
import com.zipick.notice.repository.HousingMediaRepository;
import com.zipick.notice.repository.NoticeRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import com.zipick.notice.support.AddressNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class NoticeSyncPersistenceService {

    private static final int MISSING_THRESHOLD = 2;
    private static final int NOTICE_MISSING_THRESHOLD = 1;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE,
            DateTimeFormatter.ofPattern("yyyy.MM.dd")
    );

    private final AgencyRepository agencyRepository;
    private final NoticeRepository noticeRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final HousingRepository housingRepository;
    private final HousingSupplyRepository housingSupplyRepository;
    private final HousingSupplyConditionRepository housingSupplyConditionRepository;
    private final HousingMediaRepository housingMediaRepository;
    private final NoticeStatusEvaluator statusEvaluator;
    private final NoticeTypeClassifier noticeTypeClassifier;

    public NoticeSyncPersistenceService(
            AgencyRepository agencyRepository,
            NoticeRepository noticeRepository,
            NoticeAttachmentRepository noticeAttachmentRepository,
            HousingRepository housingRepository,
            HousingSupplyRepository housingSupplyRepository,
            HousingSupplyConditionRepository housingSupplyConditionRepository,
            HousingMediaRepository housingMediaRepository,
            NoticeStatusEvaluator statusEvaluator,
            NoticeTypeClassifier noticeTypeClassifier
    ) {
        this.agencyRepository = agencyRepository;
        this.noticeRepository = noticeRepository;
        this.noticeAttachmentRepository = noticeAttachmentRepository;
        this.housingRepository = housingRepository;
        this.housingSupplyRepository = housingSupplyRepository;
        this.housingSupplyConditionRepository = housingSupplyConditionRepository;
        this.housingMediaRepository = housingMediaRepository;
        this.statusEvaluator = statusEvaluator;
        this.noticeTypeClassifier = noticeTypeClassifier;
    }

    @Transactional
    public NoticeSyncResult synchronize(List<NoticeSyncCommand> commands, Instant collectedAt) {
        return synchronize(commands, collectedAt, Set.of());
    }

    @Transactional
    public NoticeSyncResult synchronize(
            List<NoticeSyncCommand> commands,
            Instant collectedAt,
            Set<String> completedSources
    ) {
        LocalDate collectionDate = LocalDate.ofInstant(collectedAt, KOREA_ZONE);
        Map<String, Agency> agencyCache = new HashMap<>();
        Counters counters = new Counters();
        Map<String, List<NoticeSyncCommand>> commandsBySource = new LinkedHashMap<>();
        for (NoticeSyncCommand command : commands) {
            commandsBySource.computeIfAbsent(command.notice().source(), ignored -> new ArrayList<>())
                    .add(command);
        }
        for (Map.Entry<String, List<NoticeSyncCommand>> sourceEntry : commandsBySource.entrySet()) {
            synchronizeSource(sourceEntry.getKey(), sourceEntry.getValue(), collectedAt,
                    collectionDate, agencyCache, counters);
        }
        for (String source : completedSources) {
            if (!commandsBySource.containsKey(source)) {
                synchronizeSource(source, List.of(), collectedAt, collectionDate, agencyCache, counters);
            }
        }
        return counters.toResult();
    }

    private void synchronizeSource(
            String source,
            List<NoticeSyncCommand> commands,
            Instant collectedAt,
            LocalDate collectionDate,
            Map<String, Agency> agencyCache,
            Counters counters
    ) {
        Map<String, Notice> existingNotices = new LinkedHashMap<>();
        for (Notice notice : noticeRepository.findAllBySource(source)) {
            existingNotices.put(notice.getExternalNoticeId(), notice);
        }
        Set<String> replacedNoticeIds = NoticeRevisionPolicy.previousNoticeIds(commands);
        for (String replacedNoticeId : replacedNoticeIds) {
            Notice replaced = existingNotices.get(replacedNoticeId);
            if (replaced != null) {
                replaced.changeStatus(NoticeStatus.CLOSED, NoticeStatusReason.SOURCE_REPLACED);
            }
        }
        Set<String> seenNoticeIds = new HashSet<>();
        for (NoticeSyncCommand command : NoticeRevisionPolicy.latestOnly(commands)) {
            NoticeResponse sourceNotice = command.notice();
            seenNoticeIds.add(sourceNotice.noticeId());

            Notice notice = existingNotices.get(sourceNotice.noticeId());
            if (notice == null) {
                notice = new Notice(source, sourceNotice.noticeId(), sourceNotice.title(), NoticeStatus.OPEN);
                counters.createdNotices++;
            } else {
                counters.updatedNotices++;
            }

            Agency agency = resolveAgency(sourceNotice.supplyInstitutionName(), sourceNotice.url(), agencyCache);
            LocalDate announcementDate = parseDate(sourceNotice.announcementDate());
            LocalDate winnerAnnouncementDate = parseDate(sourceNotice.winnerAnnouncementDate());
            LocalDate applicationStartDate = parseDate(sourceNotice.applicationStartDate());
            LocalDate applicationEndDate = parseDate(sourceNotice.applicationEndDate());
            NoticeStatusEvaluator.StatusEvaluation status = statusEvaluator.evaluate(
                    sourceNotice.statusName(), sourceNotice.title(),
                    applicationStartDate, applicationEndDate, collectionDate
            );

            notice.synchronize(
                    agency,
                    sourceNotice.previousNoticeId(),
                    sourceNotice.title(),
                    noticeTypeClassifier.classify(firstSupplyType(sourceNotice), sourceNotice.title()),
                    firstHousingType(sourceNotice),
                    firstRegion(sourceNotice),
                    status.status(),
                    sourceNotice.statusName(),
                    status.reason(),
                    announcementDate,
                    winnerAnnouncementDate,
                    atStartOfDay(applicationStartDate),
                    atEndOfDay(applicationEndDate),
                    sourceNotice.url(),
                    sourceNotice.pcUrl(),
                    sourceNotice.mobileUrl(),
                    sourceNotice.reference(),
                    command.rawPayload(),
                    collectedAt
            );
            Notice savedNotice = noticeRepository.save(notice);
            synchronizeAttachments(savedNotice, sourceNotice.attachments());
            if (!sourceNotice.houses().isEmpty()) {
                synchronizeHousings(savedNotice, sourceNotice.houses(), collectedAt, collectionDate, counters);
            }
        }
        markMissingNotices(existingNotices, seenNoticeIds, collectionDate, counters,
                NOTICE_MISSING_THRESHOLD);
    }

    private void synchronizeAttachments(
            Notice notice,
            List<NoticeAttachmentResponse> sourceAttachments
    ) {
        for (NoticeAttachmentResponse source : sourceAttachments) {
            if (!StringUtils.hasText(source.sourceKey()) || !StringUtils.hasText(source.sourceUrl())) {
                continue;
            }
            NoticeAttachment attachment = noticeAttachmentRepository
                    .findByNoticeIdAndSourceKey(notice.getId(), source.sourceKey())
                    .orElseGet(() -> new NoticeAttachment(
                            notice, source.sourceKey(), source.attachmentType(), source.sourceUrl()
                    ));
            attachment.synchronize(
                    source.attachmentType(), source.fileName(), source.sourceUrl(), source.rawPayload()
            );
            noticeAttachmentRepository.save(attachment);
        }
    }

    private void synchronizeHousings(
            Notice notice,
            List<NoticeHousingResponse> sourceHousings,
            Instant collectedAt,
            LocalDate collectionDate,
            Counters counters
    ) {
        Map<String, Housing> existingHousings = new LinkedHashMap<>();
        for (Housing housing : housingRepository.findAllByNoticeId(notice.getId())) {
            existingHousings.put(housing.getSourceHousingKey(), housing);
        }
        Set<String> seenHousingKeys = new HashSet<>();

        for (NoticeHousingResponse sourceHousing : sourceHousings) {
            if (!StringUtils.hasText(sourceHousing.fullAddress())) {
                continue;
            }
            Housing housing = existingHousings.get(sourceHousing.sourceHousingKey());
            if (housing == null) {
                housing = findHousingByAddress(existingHousings.values(), sourceHousing);
            }
            if (housing == null) {
                housing = new Housing(
                        notice,
                        sourceHousing.sourceHousingKey(),
                        sourceHousing.complexName(),
                        sourceHousing.fullAddress()
                );
            }
            seenHousingKeys.add(housing.getSourceHousingKey());
            housing.synchronize(
                    sourceHousing.pnu(),
                    sourceHousing.complexName(),
                    sourceHousing.provinceName(),
                    sourceHousing.districtName(),
                    sourceHousing.fullAddress(),
                    normalizeAddress(sourceHousing.fullAddress()),
                    sourceHousing.roadName(),
                    sourceHousing.lotAddress(),
                    sourceHousing.roadAddress(),
                    sourceHousing.legalDongName(),
                    sourceHousing.heatingMethodName(),
                    parseInteger(sourceHousing.totalHouseholdCount()),
                    null,
                    collectedAt
            );
            Housing savedHousing = housingRepository.save(housing);
            counters.synchronizedHousings++;
            synchronizeSupplies(savedHousing, sourceHousing.supplies(), collectedAt, collectionDate, counters);
            synchronizeMedia(savedHousing, sourceHousing.media());
        }

        for (Housing housing : existingHousings.values()) {
            if (!seenHousingKeys.contains(housing.getSourceHousingKey())) {
                if (housing.markMissing(collectionDate, MISSING_THRESHOLD)) {
                    counters.inactiveHousings++;
                }
                markAllSuppliesMissing(housing, collectionDate, counters);
            }
        }
    }

    private Housing findHousingByAddress(
            java.util.Collection<Housing> existingHousings,
            NoticeHousingResponse sourceHousing
    ) {
        Set<String> sourceAddresses = addressKeys(
                sourceHousing.fullAddress(), sourceHousing.lotAddress(), sourceHousing.roadAddress()
        );
        if (sourceAddresses.isEmpty()) {
            return null;
        }
        for (Housing existing : existingHousings) {
            Set<String> existingAddresses = addressKeys(
                    existing.getAddress(), existing.getLotAddress(), existing.getRoadAddress()
            );
            if (existingAddresses.stream().anyMatch(sourceAddresses::contains)) {
                return existing;
            }
        }
        return null;
    }

    private Set<String> addressKeys(String... addresses) {
        Set<String> keys = new HashSet<>();
        for (String address : addresses) {
            String normalized = AddressNormalizer.canonicalize(address);
            if (StringUtils.hasText(normalized)) {
                keys.add(normalized);
            }
        }
        return keys;
    }

    private void synchronizeMedia(Housing housing, List<com.zipick.notice.dto.HousingMediaResponse> sourceMedia) {
        for (com.zipick.notice.dto.HousingMediaResponse source : sourceMedia) {
            if (!StringUtils.hasText(source.sourceKey()) || !StringUtils.hasText(source.sourceUrl())) {
                continue;
            }
            HousingMedia media = housingMediaRepository.findByHousingIdAndSourceKey(
                            housing.getId(), source.sourceKey())
                    .orElseGet(() -> new HousingMedia(
                            housing, source.sourceKey(), source.mediaType(), source.sourceUrl()
                    ));
            media.synchronize(source.mediaType(), source.fileName(), source.sourceUrl(), source.rawPayload());
            housingMediaRepository.save(media);
        }
    }

    private void synchronizeSupplies(
            Housing housing,
            List<NoticeSupplyResponse> sourceSupplies,
            Instant collectedAt,
            LocalDate collectionDate,
            Counters counters
    ) {
        Map<String, HousingSupply> existingSupplies = new LinkedHashMap<>();
        for (HousingSupply supply : housingSupplyRepository.findAllByHousingId(housing.getId())) {
            existingSupplies.put(supply.getSourceSupplyKey(), supply);
        }
        Set<String> seenSupplyKeys = new HashSet<>();

        for (NoticeSupplyResponse sourceSupply : sourceSupplies) {
            seenSupplyKeys.add(sourceSupply.sourceSupplyKey());
            HousingSupply supply = existingSupplies.get(sourceSupply.sourceSupplyKey());
            if (supply == null) {
                supply = new HousingSupply(housing, sourceSupply.sourceSupplyKey(), sourceSupply.externalHouseId());
            }
            supply.synchronize(
                    sourceSupply.externalHouseId(),
                    sourceSupply.houseTypeName(),
                    sourceSupply.supplyTypeName(),
                    sourceSupply.buildingDong(),
                    sourceSupply.unitNumber(),
                    sourceSupply.floor(),
                    sourceSupply.roomCount(),
                    sourceSupply.sourceRowNumber(),
                    sourceSupply.exclusiveArea(),
                    toInteger(sourceSupply.supplyCount()),
                    parseInteger(sourceSupply.supplyHouseCount()),
                    sourceSupply.rentDeposit(),
                    sourceSupply.contractDeposit(),
                    sourceSupply.interimPayment(),
                    sourceSupply.balance(),
                    sourceSupply.monthlyRent(),
                    sourceSupply.supplyStatus(),
                    sourceSupply.nearbySubwayStation(),
                    sourceSupply.subwayWalkDistanceKm(),
                    sourceSupply.elevatorAvailable(),
                    sourceSupply.communityFacilityAvailable(),
                    sourceSupply.totalParkingSpaces(),
                    sourceSupply.mechanicalParkingAvailable(),
                    sourceSupply.mechanicalParkingSpaces(),
                    sourceSupply.rawPayload(),
                    collectedAt
            );
            HousingSupply savedSupply = housingSupplyRepository.save(supply);
            synchronizeSupplyConditions(savedSupply, sourceSupply.rentalConditions());
            counters.synchronizedSupplies++;
        }

        for (HousingSupply supply : existingSupplies.values()) {
            if (!seenSupplyKeys.contains(supply.getSourceSupplyKey())
                    && supply.markMissing(collectionDate, MISSING_THRESHOLD)) {
                counters.inactiveSupplies++;
            }
        }
    }

    private void synchronizeSupplyConditions(
            HousingSupply supply,
            List<com.zipick.notice.dto.NoticeSupplyConditionResponse> sourceConditions
    ) {
        for (com.zipick.notice.dto.NoticeSupplyConditionResponse source : sourceConditions) {
            if (!StringUtils.hasText(source.conditionCode()) || !StringUtils.hasText(source.conditionName())) {
                continue;
            }
            HousingSupplyCondition condition = housingSupplyConditionRepository
                    .findByHousingSupplyIdAndConditionCode(supply.getId(), source.conditionCode())
                    .orElseGet(() -> new HousingSupplyCondition(
                            supply, source.conditionCode(), source.conditionName()
                    ));
            condition.synchronize(
                    source.conditionName(), source.rentDeposit(), source.monthlyRent(), source.rawPayload()
            );
            housingSupplyConditionRepository.save(condition);
        }
    }

    private void markMissingNotices(
            Map<String, Notice> existingNotices,
            Set<String> seenNoticeIds,
            LocalDate collectionDate,
            Counters counters,
            int cancellationThreshold
    ) {
        for (Notice notice : existingNotices.values()) {
            if (seenNoticeIds.contains(notice.getExternalNoticeId())) {
                continue;
            }
            boolean wasOpenOrUpcoming = notice.getStatus() == NoticeStatus.OPEN
                    || notice.getStatus() == NoticeStatus.UPCOMING;
            if (notice.markMissing(collectionDate, cancellationThreshold)) {
                counters.missingClosedNotices++;
            }
            if (wasOpenOrUpcoming) {
                for (Housing housing : housingRepository.findAllByNoticeId(notice.getId())) {
                    if (housing.markMissing(collectionDate, MISSING_THRESHOLD)) {
                        counters.inactiveHousings++;
                    }
                    markAllSuppliesMissing(housing, collectionDate, counters);
                }
            }
        }
    }

    private void markAllSuppliesMissing(Housing housing, LocalDate collectionDate, Counters counters) {
        for (HousingSupply supply : housingSupplyRepository.findAllByHousingId(housing.getId())) {
            if (supply.markMissing(collectionDate, MISSING_THRESHOLD)) {
                counters.inactiveSupplies++;
            }
        }
    }

    private Agency resolveAgency(String agencyName, String sourceUrl, Map<String, Agency> cache) {
        String normalizedName = StringUtils.hasText(agencyName) ? agencyName.trim() : "기관 미상";
        String code = agencyCode(normalizedName);
        Agency agency = cache.computeIfAbsent(code, key -> agencyRepository.findByCode(key)
                .orElseGet(() -> new Agency(key, normalizedName, sourceUrl)));
        agency.update(normalizedName, sourceUrl);
        return agencyRepository.save(agency);
    }

    private String agencyCode(String agencyName) {
        String normalized = agencyName.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (normalized.equals("LH") || normalized.contains("한국토지주택공사")) {
            return "LH";
        }
        if (normalized.equals("SH")
                || normalized.contains("서울주택도시공사")
                || normalized.contains("서울주택도시개발공사")) {
            return "SH";
        }
        if (normalized.equals("GH") || normalized.contains("경기주택도시공사")) {
            return "GH";
        }
        if (normalized.equals("HUG") || normalized.contains("주택도시보증공사")) {
            return "HUG";
        }
        if (normalized.equals("BMC") || normalized.contains("부산도시공사")) {
            return "BMC";
        }
        if (normalized.equals("GBDC") || normalized.contains("경상북도개발공사")) {
            return "GBDC";
        }
        return "MYHOME_" + shortHash(normalized);
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String candidate = value.trim();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(candidate, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next known external date format.
            }
        }
        return null;
    }

    private Instant atStartOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(KOREA_ZONE).toInstant();
    }

    private Instant atEndOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX).atZone(KOREA_ZONE).toInstant();
    }

    private Integer parseInteger(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.replace(",", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer toInteger(Long value) {
        if (value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private String normalizeAddress(String address) {
        return AddressNormalizer.canonicalize(address);
    }

    private String firstSupplyType(NoticeResponse notice) {
        return supplies(notice).stream()
                .map(NoticeSupplyResponse::supplyTypeName)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String firstHousingType(NoticeResponse notice) {
        return supplies(notice).stream()
                .map(NoticeSupplyResponse::houseTypeName)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private List<NoticeSupplyResponse> supplies(NoticeResponse notice) {
        List<NoticeSupplyResponse> result = new ArrayList<>();
        for (NoticeHousingResponse housing : notice.houses()) {
            result.addAll(housing.supplies());
        }
        return result;
    }

    private String firstRegion(NoticeResponse notice) {
        return notice.houses().stream()
                .map(housing -> joinRegion(housing.provinceName(), housing.districtName()))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String joinRegion(String province, String district) {
        if (!StringUtils.hasText(province)) {
            return StringUtils.hasText(district) ? district.trim() : null;
        }
        if (!StringUtils.hasText(district)) {
            return province.trim();
        }
        return province.trim() + " " + district.trim();
    }

    private static final class Counters {
        private int createdNotices;
        private int updatedNotices;
        private int synchronizedHousings;
        private int synchronizedSupplies;
        private int missingClosedNotices;
        private int inactiveHousings;
        private int inactiveSupplies;

        private NoticeSyncResult toResult() {
            return new NoticeSyncResult(
                    createdNotices,
                    updatedNotices,
                    synchronizedHousings,
                    synchronizedSupplies,
                    missingClosedNotices,
                    inactiveHousings,
                    inactiveSupplies
            );
        }
    }
}
