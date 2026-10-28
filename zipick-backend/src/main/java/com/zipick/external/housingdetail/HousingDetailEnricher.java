package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.dto.HousingMediaResponse;
import com.zipick.notice.support.AddressNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class HousingDetailEnricher {

    private static final Logger log = LoggerFactory.getLogger(HousingDetailEnricher.class);

    private final List<HousingDetailSource> sources;

    public HousingDetailEnricher(List<HousingDetailSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public NoticeResponse enrich(NoticeResponse notice) {
        List<NoticeHousingResponse> addressed = notice.houses().stream()
                .filter(housing -> StringUtils.hasText(housing.fullAddress()))
                .filter(housing -> !AddressNormalizer.isPlaceholder(housing.fullAddress()))
                .toList();
        HousingDetailResult detail = extract(notice);
        List<NoticeHousingResponse> enriched = mergeByAddress(
                addressed,
                toHousings(detail.housings(), detail.mediaByUnitKey())
        );
        return copyWithDetails(notice, enriched, detail.attachments(), detail.preservesExistingHousings(), detail.parseCheckpoints());
    }

    private HousingDetailResult extract(NoticeResponse notice) {
        for (HousingDetailSource source : sources) {
            if (!source.supports(notice)) {
                continue;
            }
            try {
                HousingDetailResult result = source.extract(notice);
                if (!result.housings().isEmpty() || !result.attachments().isEmpty() || result.preservesExistingHousings()) {
                    List<String> partialFields = HousingDetailCompleteness.partialCoreFields(result.housings());
                    if (!partialFields.isEmpty()) {
                        log.warn("상세 주택 핵심 필드가 일부 행에서 누락되었습니다: noticeId={}, source={}, fields={}",
                                notice.noticeId(), source.getClass().getSimpleName(), partialFields);
                    }
                    log.info("상세 주택 자료 처리 완료: noticeId={}, units={}, attachments={}",
                            notice.noticeId(), result.housings().size(), result.attachments().size());
                    return result;
                }
            } catch (RuntimeException exception) {
                log.warn("COLLECTOR_METRIC event=detail_error noticeId={} source={}", notice.noticeId(), source.getClass().getSimpleName());
                log.warn("상세 주택 보완 실패: noticeId={}, source={}, reason={}",
                        notice.noticeId(), source.getClass().getSimpleName(), exception.getMessage());
            }
        }
        log.info("상세 주택 보완 자료가 없습니다: noticeId={}, url={}",
                notice.noticeId(), notice.url());
        return HousingDetailResult.empty();
    }

    private List<NoticeHousingResponse> toHousings(
            List<DetailedHousingUnit> units,
            Map<String, List<DetailedHousingMedia>> mediaByUnitKey
    ) {
        Map<String, List<DetailedHousingUnit>> byAddress = new LinkedHashMap<>();
        for (DetailedHousingUnit unit : units) {
            if (!StringUtils.hasText(unit.address()) || AddressNormalizer.isPlaceholder(unit.address())) {
                continue;
            }
            byAddress.computeIfAbsent(normalizeAddress(unit.address()), ignored -> new ArrayList<>()).add(unit);
        }

        List<NoticeHousingResponse> result = new ArrayList<>();
        for (Map.Entry<String, List<DetailedHousingUnit>> entry : byAddress.entrySet()) {
            List<DetailedHousingUnit> addressUnits = entry.getValue();
            DetailedHousingUnit first = addressUnits.getFirst();
            List<NoticeSupplyResponse> supplies = addressUnits.stream()
                    .map(this::toSupply)
                    .toList();
            Map<String, HousingMediaResponse> media = new LinkedHashMap<>();
            for (DetailedHousingUnit unit : addressUnits) {
                for (DetailedHousingMedia item : mediaByUnitKey.getOrDefault(unit.sourceUnitKey(), List.of())) {
                    media.putIfAbsent(item.sourceKey(), new HousingMediaResponse(
                            item.sourceKey(), item.mediaType(), item.fileName(), item.sourceUrl(), item.rawPayload()
                    ));
                }
            }
            result.add(new NoticeHousingResponse(
                    "DETAIL:ADDRESS:" + shortHash(entry.getKey()),
                    first.complexName(),
                    first.provinceName(),
                    first.districtName(),
                    first.address(),
                    first.lotAddress(),
                    first.roadAddress(),
                    AddressNormalizer.extractRoadName(first.address()),
                    AddressNormalizer.extractLegalDongName(first.address()),
                    null,
                    null,
                    null,
                    supplies,
                    List.copyOf(media.values())
            ));
        }
        return List.copyOf(result);
    }

    private NoticeSupplyResponse toSupply(DetailedHousingUnit unit) {
        return new NoticeSupplyResponse(
                unit.sourceUnitKey(),
                null,
                unit.housingType(),
                unit.supplyType(),
                unit.buildingDong(),
                unit.unitNumber(),
                unit.floor(),
                unit.roomCount(),
                unit.exclusiveArea(),
                unit.supplyCount() == null ? 1L : unit.supplyCount(),
                Long.toString(unit.supplyCount() == null ? 1L : unit.supplyCount()),
                unit.rentDeposit(),
                null,
                null,
                null,
                unit.monthlyRent(),
                unit.rawPayload(),
                unit.sourceRowNumber(),
                unit.supplyStatus(),
                unit.nearbySubwayStation(),
                unit.subwayWalkDistanceKm(),
                unit.elevatorAvailable(),
                unit.communityFacilityAvailable(),
                unit.totalParkingSpaces(),
                unit.mechanicalParkingAvailable(),
                unit.mechanicalParkingSpaces(),
                unit.rentalConditions().stream()
                        .map(condition -> new com.zipick.notice.dto.NoticeSupplyConditionResponse(
                                condition.conditionCode(), condition.conditionName(),
                                condition.rentDeposit(), condition.monthlyRent(), condition.rawPayload()
                        ))
                        .toList()
        );
    }

    private List<NoticeHousingResponse> mergeByAddress(
            List<NoticeHousingResponse> original,
            List<NoticeHousingResponse> enriched
    ) {
        Map<String, NoticeHousingResponse> merged = new LinkedHashMap<>();
        for (NoticeHousingResponse housing : original) {
            NoticeHousingResponse completed = fillAddressDerivedFields(housing);
            merged.put(normalizeAddress(completed.fullAddress()), completed);
        }
        for (NoticeHousingResponse housing : enriched) {
            String key = normalizeAddress(housing.fullAddress());
            NoticeHousingResponse previous = merged.get(key);
            if (previous == null) {
                merged.put(key, housing);
                continue;
            }
            merged.put(key, new NoticeHousingResponse(
                    previous.sourceHousingKey(),
                    firstText(previous.complexName(), housing.complexName()),
                    firstText(previous.provinceName(), housing.provinceName()),
                    firstText(previous.districtName(), housing.districtName()),
                    previous.fullAddress(),
                    firstText(previous.lotAddress(), housing.lotAddress()),
                    firstText(previous.roadAddress(), housing.roadAddress()),
                    firstText(previous.roadName(), housing.roadName()),
                    firstText(previous.legalDongName(), housing.legalDongName()),
                    firstText(previous.pnu(), housing.pnu()),
                    firstText(previous.heatingMethodName(), housing.heatingMethodName()),
                    firstText(previous.totalHouseholdCount(), housing.totalHouseholdCount()),
                    housing.supplies(),
                    mergeMedia(previous.media(), housing.media())
            ));
        }
        return List.copyOf(merged.values());
    }

    private NoticeHousingResponse fillAddressDerivedFields(NoticeHousingResponse housing) {
        return new NoticeHousingResponse(
                housing.sourceHousingKey(),
                firstText(housing.complexName(), AddressNormalizer.extractBuildingName(housing.fullAddress())),
                housing.provinceName(),
                housing.districtName(),
                housing.fullAddress(),
                housing.lotAddress(),
                housing.roadAddress(),
                firstText(housing.roadName(), AddressNormalizer.extractRoadName(housing.fullAddress())),
                firstText(housing.legalDongName(), AddressNormalizer.extractLegalDongName(housing.fullAddress())),
                housing.pnu(),
                housing.heatingMethodName(),
                housing.totalHouseholdCount(),
                housing.supplies(),
                housing.media()
        );
    }

    private List<HousingMediaResponse> mergeMedia(
            List<HousingMediaResponse> primary,
            List<HousingMediaResponse> supplement
    ) {
        Map<String, HousingMediaResponse> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.sourceKey(), item));
        supplement.forEach(item -> merged.putIfAbsent(item.sourceKey(), item));
        return List.copyOf(merged.values());
    }

    private NoticeResponse copyWithDetails(
            NoticeResponse notice,
            List<NoticeHousingResponse> houses,
            List<NoticeAttachmentResponse> attachments,
            boolean preservesExistingHousings,
            List<com.zipick.notice.dto.ParseCheckpoint> checkpoints
    ) {
        return new NoticeResponse(
                notice.source(),
                notice.noticeId(),
                notice.statusName(),
                notice.title(),
                notice.supplyInstitutionName(),
                notice.previousNoticeId(),
                notice.announcementDate(),
                notice.winnerAnnouncementDate(),
                notice.applicationStartDate(),
                notice.applicationEndDate(),
                notice.reference(),
                notice.url(),
                notice.pcUrl(),
                notice.mobileUrl(),
                houses,
                attachments,
                preservesExistingHousings,
                checkpoints
        );
    }

    private String normalizeAddress(String address) {
        return AddressNormalizer.canonicalize(address);
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
