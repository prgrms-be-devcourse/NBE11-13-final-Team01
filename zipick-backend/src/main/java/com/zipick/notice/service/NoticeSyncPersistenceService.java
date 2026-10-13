package com.zipick.notice.service;

import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.repository.AgencyRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.NoticeRepository;
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

    private static final String SOURCE = "MYHOME";
    private static final int MISSING_THRESHOLD = 2;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE,
            DateTimeFormatter.ofPattern("yyyy.MM.dd")
    );

    private final AgencyRepository agencyRepository;
    private final NoticeRepository noticeRepository;
    private final HousingRepository housingRepository;
    private final HousingSupplyRepository housingSupplyRepository;
    private final NoticeStatusEvaluator statusEvaluator;
    private final NoticeTypeClassifier noticeTypeClassifier;

    public NoticeSyncPersistenceService(
            AgencyRepository agencyRepository,
            NoticeRepository noticeRepository,
            HousingRepository housingRepository,
            HousingSupplyRepository housingSupplyRepository,
            NoticeStatusEvaluator statusEvaluator,
            NoticeTypeClassifier noticeTypeClassifier
    ) {
        this.agencyRepository = agencyRepository;
        this.noticeRepository = noticeRepository;
        this.housingRepository = housingRepository;
        this.housingSupplyRepository = housingSupplyRepository;
        this.statusEvaluator = statusEvaluator;
        this.noticeTypeClassifier = noticeTypeClassifier;
    }

    @Transactional
    public NoticeSyncResult synchronize(List<NoticeSyncCommand> commands, Instant collectedAt) {
        LocalDate collectionDate = LocalDate.ofInstant(collectedAt, KOREA_ZONE);
        Map<String, Notice> existingNotices = new LinkedHashMap<>();
        for (Notice notice : noticeRepository.findAllBySource(SOURCE)) {
            existingNotices.put(notice.getExternalNoticeId(), notice);
        }

        Set<String> seenNoticeIds = new HashSet<>();
        Map<String, Agency> agencyCache = new HashMap<>();
        Counters counters = new Counters();

        for (NoticeSyncCommand command : commands) {
            NoticeResponse sourceNotice = command.notice();
            seenNoticeIds.add(sourceNotice.noticeId());

            Notice notice = existingNotices.get(sourceNotice.noticeId());
            if (notice == null) {
                notice = new Notice(SOURCE, sourceNotice.noticeId(), sourceNotice.title(), NoticeStatus.OPEN);
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
                    sourceNotice.statusName(), applicationStartDate, applicationEndDate, collectionDate
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
            synchronizeHousings(savedNotice, sourceNotice.houses(), collectedAt, collectionDate, counters);
        }

        markMissingNotices(existingNotices, seenNoticeIds, collectionDate, counters);
        return counters.toResult();
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
            seenHousingKeys.add(sourceHousing.sourceHousingKey());
            Housing housing = existingHousings.get(sourceHousing.sourceHousingKey());
            if (housing == null) {
                housing = new Housing(
                        notice,
                        sourceHousing.sourceHousingKey(),
                        sourceHousing.complexName(),
                        sourceHousing.fullAddress()
                );
            }
            housing.synchronize(
                    sourceHousing.pnu(),
                    sourceHousing.complexName(),
                    sourceHousing.provinceName(),
                    sourceHousing.districtName(),
                    sourceHousing.fullAddress(),
                    normalizeAddress(sourceHousing.fullAddress()),
                    sourceHousing.roadName(),
                    sourceHousing.legalDongName(),
                    sourceHousing.heatingMethodName(),
                    parseInteger(sourceHousing.totalHouseholdCount()),
                    null,
                    collectedAt
            );
            Housing savedHousing = housingRepository.save(housing);
            counters.synchronizedHousings++;
            synchronizeSupplies(savedHousing, sourceHousing.supplies(), collectedAt, collectionDate, counters);
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
                    sourceSupply.exclusiveArea(),
                    toInteger(sourceSupply.supplyCount()),
                    parseInteger(sourceSupply.supplyHouseCount()),
                    sourceSupply.rentDeposit(),
                    sourceSupply.contractDeposit(),
                    sourceSupply.interimPayment(),
                    sourceSupply.balance(),
                    sourceSupply.monthlyRent(),
                    sourceSupply.rawPayload(),
                    collectedAt
            );
            housingSupplyRepository.save(supply);
            counters.synchronizedSupplies++;
        }

        for (HousingSupply supply : existingSupplies.values()) {
            if (!seenSupplyKeys.contains(supply.getSourceSupplyKey())
                    && supply.markMissing(collectionDate, MISSING_THRESHOLD)) {
                counters.inactiveSupplies++;
            }
        }
    }

    private void markMissingNotices(
            Map<String, Notice> existingNotices,
            Set<String> seenNoticeIds,
            LocalDate collectionDate,
            Counters counters
    ) {
        for (Notice notice : existingNotices.values()) {
            if (seenNoticeIds.contains(notice.getExternalNoticeId())) {
                continue;
            }
            boolean wasOpenOrUpcoming = notice.getStatus() == NoticeStatus.OPEN
                    || notice.getStatus() == NoticeStatus.UPCOMING;
            if (notice.markMissing(collectionDate, MISSING_THRESHOLD)) {
                counters.cancelledNotices++;
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
        if (normalized.equals("SH") || normalized.contains("서울주택도시공사")) {
            return "SH";
        }
        if (normalized.equals("GH") || normalized.contains("경기주택도시공사")) {
            return "GH";
        }
        if (normalized.equals("HUG") || normalized.contains("주택도시보증공사")) {
            return "HUG";
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
        private int cancelledNotices;
        private int inactiveHousings;
        private int inactiveSupplies;

        private NoticeSyncResult toResult() {
            return new NoticeSyncResult(
                    createdNotices,
                    updatedNotices,
                    synchronizedHousings,
                    synchronizedSupplies,
                    cancelledNotices,
                    inactiveHousings,
                    inactiveSupplies
            );
        }
    }
}
