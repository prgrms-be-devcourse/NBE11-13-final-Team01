package com.zipick.notice.service;

import com.zipick.external.kakao.client.KakaoAddressClient;
import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.external.kakao.dto.KakaoAddressSearchResponse;
import com.zipick.external.kakao.dto.KakaoKeywordSearchResponse;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.support.AddressNormalizer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HousingGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(HousingGeocodingService.class);
    private static final Pattern PARENTHESIZED = Pattern.compile("\\s*\\([^)]*\\)\\s*");
    private static final Pattern UNIT_SUFFIX = Pattern.compile(
            "\\s+(?:제?\\d+층(?:\\s+제?\\d+호)?|제?\\d+호|\\d+동\\s+\\d+호)\\s*$"
    );
    private static final Pattern MULTI_LOT = Pattern.compile(
            "^(?<base>.*(?:동|리|가))\\s+(?<lots>\\d+(?:-\\d+)?(?:\\s*,\\s*\\d+(?:-\\d+)?)+)(?:\\s.*)?$"
    );
    private static final Pattern PROVINCE_PREFIX = Pattern.compile(
            "^(?:서울특별시|부산광역시|대구광역시|인천광역시|광주광역시|대전광역시|울산광역시|"
                    + "세종특별자치시|경기도|강원특별자치도|강원도|충청북도|충청남도|"
                    + "전북특별자치도|전라북도|전라남도|경상북도|경상남도|제주특별자치도|제주도)\\s+"
    );
    private static final Pattern HOUSING_PLACE = Pattern.compile(
            "아파트|주택|빌라|오피스텔|타운|하우스|팰리스|단지|푸르지오|래미안|리엔파크|포레나|힐스테이트|자이"
    );
    private static final Pattern NON_HOUSING_PLACE = Pattern.compile(
            "한국토지주택공사|LH|사업단|주거복지센터|지역본부|견본주택|모델하우스|홍보관",
            Pattern.CASE_INSENSITIVE
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
        int excluded = 0;
        int apiCalls = 0;

        for (Housing housing : pending) {
            List<String> queries = createSearchQueries(housing.getAddress(), housing.getRoadName());
            if (queries.isEmpty()) {
                log.info("좌표 검색에서 제외한 잘못된 주소입니다. housingId={}, address={}",
                        housing.getId(), housing.getAddress());
                excluded++;
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
                        unresolved += pending.size() - updated - unresolved - excluded;
                        apiFailed = true;
                        break;
                    }
                }
                if (cached.isPresent()) {
                    resolved = cached;
                    break;
                }
            }
            if (resolved.isEmpty() && !apiFailed) {
                String keywordQuery = queries.getFirst();
                try {
                    resolved = findUnambiguousHousingKeyword(keywordQuery);
                    apiCalls++;
                } catch (RuntimeException exception) {
                    log.warn("카카오 키워드 주소 보완 실패로 좌표 변환을 중단합니다. query={}, reason={}",
                            keywordQuery, exception.getMessage());
                    unresolved += pending.size() - updated - unresolved - excluded;
                    apiFailed = true;
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

        GeocodingResult result = new GeocodingResult(
                pending.size() - excluded, updated, unresolved, excluded, false, apiCalls
        );
        log.info("주택 주소 좌표 변환 완료: 대상={}, 갱신={}, 미해결={}, 잘못된 주소 제외={}, 카카오 호출={}",
                result.targets(), result.updated(), result.unresolved(), result.excluded(), result.apiCalls());
        return result;
    }

    static List<String> createSearchQueries(String address, String roadName) {
        if (!StringUtils.hasText(address) || AddressNormalizer.isPlaceholder(address)) {
            return List.of();
        }

        Set<String> queries = new LinkedHashSet<>();
        String normalizedAddress = collapseRepeatedSegments(address);
        String roadAddress = toRoadAddressQuery(normalizedAddress, roadName);
        if (StringUtils.hasText(roadAddress)) {
            addQueryVariants(queries, roadAddress);
        }
        String cleanedAddress = toSearchQuery(normalizedAddress);
        if (StringUtils.hasText(cleanedAddress)) {
            Matcher multipleLots = MULTI_LOT.matcher(cleanedAddress);
            if (multipleLots.matches()) {
                for (String lot : multipleLots.group("lots").split("\\s*,\\s*")) {
                    addQueryVariants(queries, multipleLots.group("base") + " " + lot);
                }
            } else {
                addQueryVariants(queries, cleanedAddress);
            }
        }
        return List.copyOf(queries);
    }

    private static void addQueryVariants(Set<String> queries, String query) {
        String normalized = query.replaceAll("\\s+", " ").trim();
        queries.add(normalized);
        String withoutProvince = PROVINCE_PREFIX.matcher(normalized).replaceFirst("");
        if (!withoutProvince.equals(normalized)) {
            queries.add(withoutProvince);
        }
    }

    static String collapseRepeatedSegments(String address) {
        String normalized = address.trim().replaceAll("\\s+", " ");
        List<String> tokens = new java.util.ArrayList<>(List.of(normalized.split(" ")));
        boolean changed;
        do {
            changed = false;
            for (int blockSize = Math.min(4, tokens.size() / 2); blockSize >= 1 && !changed; blockSize--) {
                for (int start = 0; start + blockSize * 2 <= tokens.size(); start++) {
                    if (tokens.subList(start, start + blockSize)
                            .equals(tokens.subList(start + blockSize, start + blockSize * 2))) {
                        tokens.subList(start + blockSize, start + blockSize * 2).clear();
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return String.join(" ", tokens);
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

    private Optional<GeocodedAddress> findUnambiguousHousingKeyword(String query) {
        KakaoKeywordSearchResponse response = addressClient.searchKeyword(query);
        String queryKey = AddressNormalizer.canonicalize(query);
        Map<String, KakaoKeywordSearchResponse.Document> candidates = new java.util.LinkedHashMap<>();
        for (KakaoKeywordSearchResponse.Document document : response.documents()) {
            if (!StringUtils.hasText(document.placeName())
                    || !HOUSING_PLACE.matcher(document.placeName()).find()
                    || NON_HOUSING_PLACE.matcher(document.placeName()).find()
                    || !StringUtils.hasText(document.x())
                    || !StringUtils.hasText(document.y())) {
                continue;
            }
            String lotKey = AddressNormalizer.canonicalize(document.addressName());
            String roadKey = AddressNormalizer.canonicalize(document.roadAddressName());
            if (!queryKey.equals(lotKey) && !queryKey.equals(roadKey)) {
                continue;
            }
            candidates.putIfAbsent(document.x() + ":" + document.y(), document);
        }
        if (candidates.size() != 1) {
            log.info("주택 키워드 검색 결과가 확정적이지 않아 저장하지 않습니다. query={}, count={}",
                    query, candidates.size());
            return Optional.empty();
        }

        KakaoKeywordSearchResponse.Document document = candidates.values().iterator().next();
        String normalizedAddress = firstText(document.roadAddressName(), document.addressName());
        String[] region = regionParts(normalizedAddress);
        return Optional.of(new GeocodedAddress(
                normalizedAddress,
                region[0],
                region[1],
                null,
                AddressNormalizer.extractRoadName(document.roadAddressName()),
                document.placeName(),
                new BigDecimal(document.y()),
                new BigDecimal(document.x())
        ));
    }

    private String[] regionParts(String address) {
        String[] result = new String[2];
        if (!StringUtils.hasText(address)) {
            return result;
        }
        String[] tokens = address.trim().split("\\s+");
        result[0] = tokens.length > 0 ? tokens[0] : null;
        result[1] = tokens.length > 1 ? tokens[1] : null;
        return result;
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
            int excluded,
            boolean skipped,
            int apiCalls
    ) {
        public GeocodingResult(int targets, int updated, int unresolved, boolean skipped) {
            this(targets, updated, unresolved, 0, skipped, 0);
        }
    }
}
