package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
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
        boolean needsEnrichment = notice.houses().stream()
                .anyMatch(housing -> !StringUtils.hasText(housing.fullAddress())
                        || !StringUtils.hasText(housing.complexName())
                        || !StringUtils.hasText(housing.roadName()));
        if (!needsEnrichment) {
            return notice;
        }

        List<NoticeHousingResponse> addressed = notice.houses().stream()
                .filter(housing -> StringUtils.hasText(housing.fullAddress()))
                .toList();
        List<DetailedHousingUnit> units = extract(notice);
        List<NoticeHousingResponse> enriched = mergeByAddress(addressed, toHousings(units));
        return copyWithHousings(notice, enriched);
    }

    private List<DetailedHousingUnit> extract(NoticeResponse notice) {
        for (HousingDetailSource source : sources) {
            if (!source.supports(notice)) {
                continue;
            }
            try {
                List<DetailedHousingUnit> units = source.extract(notice);
                if (!units.isEmpty()) {
                    log.info("상세 주택 보완 성공: noticeId={}, units={}", notice.noticeId(), units.size());
                    return units;
                }
            } catch (RuntimeException exception) {
                log.warn("상세 주택 보완 실패: noticeId={}, source={}, reason={}",
                        notice.noticeId(), source.getClass().getSimpleName(), exception.getMessage());
            }
        }
        log.warn("주소 없는 공고를 주택 테이블에서 제외합니다: noticeId={}, url={}",
                notice.noticeId(), notice.url());
        return List.of();
    }

    private List<NoticeHousingResponse> toHousings(List<DetailedHousingUnit> units) {
        Map<String, List<DetailedHousingUnit>> byAddress = new LinkedHashMap<>();
        for (DetailedHousingUnit unit : units) {
            if (!StringUtils.hasText(unit.address())) {
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
            result.add(new NoticeHousingResponse(
                    "DETAIL:ADDRESS:" + shortHash(entry.getKey()),
                    first.complexName(),
                    first.provinceName(),
                    first.districtName(),
                    first.address(),
                    AddressNormalizer.extractRoadName(first.address()),
                    AddressNormalizer.extractLegalDongName(first.address()),
                    null,
                    null,
                    null,
                    supplies
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
                1L,
                "1",
                unit.rentDeposit(),
                null,
                null,
                null,
                unit.monthlyRent(),
                unit.rawPayload()
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
                    firstText(previous.roadName(), housing.roadName()),
                    firstText(previous.legalDongName(), housing.legalDongName()),
                    firstText(previous.pnu(), housing.pnu()),
                    firstText(previous.heatingMethodName(), housing.heatingMethodName()),
                    firstText(previous.totalHouseholdCount(), housing.totalHouseholdCount()),
                    housing.supplies()
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
                firstText(housing.roadName(), AddressNormalizer.extractRoadName(housing.fullAddress())),
                firstText(housing.legalDongName(), AddressNormalizer.extractLegalDongName(housing.fullAddress())),
                housing.pnu(),
                housing.heatingMethodName(),
                housing.totalHouseholdCount(),
                housing.supplies()
        );
    }

    private NoticeResponse copyWithHousings(NoticeResponse notice, List<NoticeHousingResponse> houses) {
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
                houses
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
