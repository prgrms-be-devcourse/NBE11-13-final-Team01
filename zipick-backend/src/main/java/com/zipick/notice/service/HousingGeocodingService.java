package com.zipick.notice.service;

import com.zipick.external.kakao.client.KakaoAddressClient;
import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.external.kakao.dto.KakaoAddressSearchResponse;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.repository.HousingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class HousingGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(HousingGeocodingService.class);
    private static final Pattern PARENTHESIZED = Pattern.compile("\\s*\\([^)]*\\)\\s*");
    private static final Pattern UNIT_SUFFIX = Pattern.compile(
            "\\s+(?:제?\\d+층(?:\\s+제?\\d+호)?|제?\\d+호|\\d+동\\s+\\d+호)\\s*$"
    );

    private final HousingRepository housingRepository;
    private final KakaoAddressClient addressClient;
    private final KakaoLocalApiProperties properties;
    private final HousingGeocodingPersistenceService persistenceService;

    public HousingGeocodingService(
            HousingRepository housingRepository,
            KakaoAddressClient addressClient,
            KakaoLocalApiProperties properties,
            HousingGeocodingPersistenceService persistenceService
    ) {
        this.housingRepository = housingRepository;
        this.addressClient = addressClient;
        this.properties = properties;
        this.persistenceService = persistenceService;
    }

    public GeocodingResult enrichMissingCoordinates() {
        if (!properties.isConfigured()) {
            log.warn("카카오 REST API 키가 없어 주택 주소 좌표 변환을 건너뜁니다.");
            return new GeocodingResult(0, 0, 0, true);
        }

        List<Housing> pending = housingRepository.findActiveWithoutCoordinates();
        Map<String, Optional<GeocodedAddress>> cache = new HashMap<>();
        int updated = 0;
        int unresolved = 0;
        int apiCalls = 0;

        for (Housing housing : pending) {
            List<String> queries = createSearchQueries(housing.getAddress(), housing.getRoadName());
            if (queries.isEmpty()) {
                unresolved++;
                continue;
            }

            Optional<GeocodedAddress> resolved = Optional.empty();
            boolean apiFailed = false;
            for (String query : queries) {
                Optional<GeocodedAddress> cached;
                if (cache.containsKey(query)) {
                    cached = cache.get(query);
                } else {
                    try {
                        cached = findUnambiguous(query);
                        cache.put(query, cached);
                        apiCalls++;
                    } catch (RuntimeException exception) {
                        log.warn("카카오 주소 검색 실패로 좌표 변환을 중단합니다. query={}, reason={}",
                                query, exception.getMessage());
                        unresolved += pending.size() - updated - unresolved;
                        apiFailed = true;
                        break;
                    }
                }
                if (cached.isPresent()) {
                    resolved = cached;
                    break;
                }
            }
            if (apiFailed) {
                break;
            }

            if (resolved.isPresent()
                    && persistenceService.updateIfCoordinatesMissing(housing.getId(), resolved.get())) {
                updated++;
            } else {
                unresolved++;
            }
        }

        GeocodingResult result = new GeocodingResult(pending.size(), updated, unresolved, false, apiCalls);
        log.info("주택 주소 좌표 변환 완료: 대상={}, 갱신={}, 미해결={}, 카카오 호출={}",
                result.targets(), result.updated(), result.unresolved(), result.apiCalls());
        return result;
    }

    static List<String> createSearchQueries(String address, String roadName) {
        if (!StringUtils.hasText(address)) {
            return List.of();
        }

        Set<String> queries = new LinkedHashSet<>();
        String roadAddress = toRoadAddressQuery(address, roadName);
        if (StringUtils.hasText(roadAddress)) {
            queries.add(roadAddress);
        }
        String cleanedAddress = toSearchQuery(address);
        if (StringUtils.hasText(cleanedAddress)) {
            queries.add(cleanedAddress);
        }
        return List.copyOf(queries);
    }

    static String toRoadAddressQuery(String address, String roadName) {
        if (!StringUtils.hasText(address) || !StringUtils.hasText(roadName)) {
            return null;
        }

        Pattern roadWithBuildingNumber = Pattern.compile(
                Pattern.quote(roadName.trim()) + "\\s*\\d+(?:-\\d+)?"
        );
        java.util.regex.Matcher matcher = roadWithBuildingNumber.matcher(address);
        if (!matcher.find()) {
            return null;
        }
        return address.substring(0, matcher.end()).replaceAll("\\s+", " ").trim();
    }

    private Optional<GeocodedAddress> findUnambiguous(String query) {
        KakaoAddressSearchResponse response = addressClient.search(query);
        if (response.documents().size() != 1) {
            log.info("주소 검색 결과가 확정적이지 않아 저장하지 않습니다. query={}, count={}",
                    query, response.documents().size());
            return Optional.empty();
        }

        KakaoAddressSearchResponse.Document document = response.documents().getFirst();
        KakaoAddressSearchResponse.Address address = document.address();
        KakaoAddressSearchResponse.RoadAddress road = document.roadAddress();
        if (!StringUtils.hasText(document.x()) || !StringUtils.hasText(document.y())) {
            return Optional.empty();
        }

        return Optional.of(new GeocodedAddress(
                firstText(road == null ? null : road.addressName(), address == null ? null : address.addressName()),
                firstText(road == null ? null : road.provinceName(), address == null ? null : address.provinceName()),
                firstText(road == null ? null : road.districtName(), address == null ? null : address.districtName()),
                firstText(address == null ? null : address.legalDongName(), road == null ? null : road.legalDongName()),
                road == null ? null : road.roadName(),
                road == null ? null : road.buildingName(),
                new BigDecimal(document.y()),
                new BigDecimal(document.x())
        ));
    }

    static String toSearchQuery(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String withoutParentheses = PARENTHESIZED.matcher(address.trim()).replaceAll(" ");
        return UNIT_SUFFIX.matcher(withoutParentheses).replaceFirst("").replaceAll("\\s+", " ").trim();
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim()
                : StringUtils.hasText(fallback) ? fallback.trim() : null;
    }

    public record GeocodingResult(
            int targets,
            int updated,
            int unresolved,
            boolean skipped,
            int apiCalls
    ) {
        public GeocodingResult(int targets, int updated, int unresolved, boolean skipped) {
            this(targets, updated, unresolved, skipped, 0);
        }
    }
}
