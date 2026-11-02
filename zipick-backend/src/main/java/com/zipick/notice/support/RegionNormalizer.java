package com.zipick.notice.support;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 행정구역(시도·시군구) 표준화. **순수 로직이다** — DB·Spring·외부 API에 의존하지 않는다.
 *
 * <p>수집 경로마다 지역 표기가 달라 같은 지역이 여러 값으로 저장되던 문제를 한곳에서 정리한다.
 * <ul>
 *   <li>MyHome 공공 API — {@code brtcNm}/{@code signguNm} 원본 (대체로 정확, 일반구 포함)
 *   <li>첨부파일 파서 — 공고문 주소를 잘라 쓰므로 옛 명칭·1토큰 절단·토큰 밀림이 생긴다
 *   <li>Kakao geocoding — {@code region_1depth_name}이 <b>축약형</b>이다 ("서울", "경기")
 * </ul>
 *
 * <p>호출 지점은 {@code Housing} 엔티티 한 곳이다(세 쓰기 경로가 모두 거친다).
 * 수집기마다 따로 정규화하지 않는다.
 *
 * <p><b>원칙: 근거가 있을 때만 바꾼다.</b> 확정할 수 없으면 원본을 유지하거나 비워 두고,
 * 임의로 추측하지 않는다.
 */
public final class RegionNormalizer {

    /** 정규화 결과. 둘 다 null 일 수 있다(지역 미상) */
    public record Region(String province, String district) {}

    /** 현재 공식 광역 행정구역 */
    private static final Set<String> CANONICAL_PROVINCES = Set.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "대전광역시", "울산광역시",
            "세종특별자치시", "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도",
            "경상북도", "경상남도", "제주특별자치도", "전남광주통합특별시"
    );

    /**
     * 1:1 개칭 — 가리키는 지역 범위가 완전히 같다.
     *
     * <p>범위가 같으므로 <b>주소 문자열 중복 비교에도 안전하다</b>. {@link AddressNormalizer}가 이 표를 쓴다.
     */
    public static final Map<String, String> RENAMED_PROVINCES = Map.of(
            "전라북도", "전북특별자치도",
            "강원도", "강원특별자치도",
            "제주도", "제주특별자치도"
    );

    /**
     * 행정구역 통합 — 가리키는 범위가 달라진다.
     *
     * <p>⚠️ {@link AddressNormalizer#canonicalize}에는 <b>일부러 넣지 않는다.</b> 그 결과가
     * {@code source_housing_key}(예: {@code DETAIL:ADDRESS:<hash>})의 재료라서, 매핑을 바꾸면
     * 다음 수집에서 같은 주택이 다른 키로 보여 중복 행이 생긴다.
     */
    private static final Map<String, String> MERGED_PROVINCES = Map.of(
            "광주광역시", "전남광주통합특별시",
            "전라남도", "전남광주통합특별시"
    );

    /**
     * 축약형. Kakao {@code region_1depth_name}과 공고문 표기를 함께 받는다.
     *
     * <p>⚠️ "광주"는 일부러 넣지 않았다 — <b>광주광역시(현 전남광주통합특별시)와 경기도 광주시를
     * 문맥 없이 구분할 수 없다.</b> 같은 이유로 "○○시" 꼴 축약도 "서울시" 하나만 둔다.
     */
    private static final Map<String, String> SHORT_FORMS = Map.ofEntries(
            Map.entry("서울", "서울특별시"),
            Map.entry("서울시", "서울특별시"),
            Map.entry("부산", "부산광역시"),
            Map.entry("대구", "대구광역시"),
            Map.entry("인천", "인천광역시"),
            Map.entry("대전", "대전광역시"),
            Map.entry("울산", "울산광역시"),
            Map.entry("세종", "세종특별자치시"),
            Map.entry("경기", "경기도"),
            Map.entry("강원", "강원특별자치도"),
            Map.entry("충북", "충청북도"),
            Map.entry("충남", "충청남도"),
            Map.entry("전북", "전북특별자치도"),
            Map.entry("전남", "전남광주통합특별시"),
            Map.entry("경북", "경상북도"),
            Map.entry("경남", "경상남도"),
            Map.entry("제주", "제주특별자치도")
    );

    /** 공백 없는 주소에서 시도를 떼어낼 때 쓴다. 긴 이름부터 봐야 "전라북도"가 "전북"에 가려지지 않는다 */
    private static final List<String> PROVINCE_PREFIXES = provincePrefixes();

    /** 시군구 한 토큰. 숫자가 섞인 도로명은 걸리지 않는다 */
    private static final Pattern DISTRICT_TOKEN = Pattern.compile("^[가-힣]{1,6}(?:시|군|구)$");
    /**
     * 광역 행정구역 접미사. 아는 시도가 아니어도 이 꼴이면 <b>하위 행정구역이 밀려 들어온 것으로 보지 않는다</b>
     * (앞으로 생길 새 시도명을 "시"로 끝난다는 이유로 시군구 취급하면 안 된다).
     */
    private static final Pattern PROVINCE_SUFFIX = Pattern.compile("(?:특별시|광역시|특별자치시|도|특별자치도)$");
    /** 시 + 일반구 (이미 정밀도가 확보된 값) */
    private static final Pattern CITY_WITH_GU = Pattern.compile("^[가-힣]{1,5}시\\s[가-힣]{1,5}구$");
    /** 일반구를 붙일 수 있는 '○○시' 꼴 */
    private static final Pattern CITY_ONLY = Pattern.compile("^[가-힣]{1,5}시$");
    private static final Pattern GU_TOKEN = Pattern.compile("^[가-힣]{1,5}구$");
    /** 행정구역이 아닌 개발지구 이름. "도청이전신도시"처럼 '시'로 끝나 시군구로 오인되는 값을 막는다 */
    private static final Pattern NOT_ADMINISTRATIVE = Pattern.compile("(?:신도시|지구|단지|택지|블록|구역)$");

    private RegionNormalizer() {
    }

    /**
     * 시도·시군구를 표준값으로 바꾼다.
     *
     * @param province         저장되어 있던 시도 (수집기·Kakao 어느 쪽이든)
     * @param district         저장되어 있던 시군구
     * @param address          수집 원문 주소
     * @param normalizedAddress Kakao가 돌려준 표준 주소(없을 수 있다). 있으면 주소보다 먼저 믿는다
     */
    public static Region normalize(String province, String district, String address, String normalizedAddress) {
        String rawProvince = blankToNull(province);
        String rawDistrict = blankToNull(district);
        String addr = blankToNull(address);
        String normAddr = blankToNull(normalizedAddress);

        // 1) 아는 시도(정식명·개칭·통합·축약)면 여기서 끝난다.
        //    "서울"·"경기"처럼 접미사가 없는 정상 축약형을 아래 2)의 구조 오류로 오판하면 안 되므로 이 판정이 먼저다.
        String canonical = canonicalProvince(rawProvince);
        String shifted = null;

        if (canonical == null && rawProvince != null) {
            if (looksLikeDistrict(rawProvince)) {
                // 2) 아는 시도가 아닌데 시·군·구 꼴이다 → 시도 자리에 하위 행정구역이 밀려 들어온 것.
                //    원래 district(읍·도로명 등)는 시군구가 아니므로 버린다.
                shifted = rawProvince;
            } else if (!NOT_ADMINISTRATIVE.matcher(rawProvince).find()) {
                // 모르는 값이라고 지우지 않는다. 새 행정구역명이 생겼을 수 있다.
                canonical = rawProvince;
            }
            // NOT_ADMINISTRATIVE 면 행정구역이 아니므로 버린다(= null 유지)
        }

        String resultDistrict = shifted != null ? shifted : rawDistrict;

        // 3) 시도가 비어 있으면 주소에서 찾는다. 찾은 값도 반드시 표준화를 다시 거친다.
        if (canonical == null) {
            canonical = provinceFromAddress(normAddr);
            if (canonical == null) canonical = provinceFromAddress(addr);
        }

        // 4) 시군구가 행정구역 꼴이 아니면 버린다 (읍·면·도로명·개발지구 등)
        if (resultDistrict != null && !isValidDistrict(resultDistrict)) {
            resultDistrict = null;
        }

        // 5) 비어 있으면 주소에서 찾고, '○○시'까지만 있으면 주소에 일반구가 있을 때만 정밀도를 올린다.
        String fromAddress = districtFromAddress(normAddr, canonical);
        if (fromAddress == null) fromAddress = districtFromAddress(addr, canonical);

        if (resultDistrict == null) {
            resultDistrict = fromAddress;
        } else if (CITY_ONLY.matcher(resultDistrict).matches()
                && fromAddress != null && fromAddress.startsWith(resultDistrict + " ")) {
            resultDistrict = fromAddress;
        }

        return new Region(canonical, resultDistrict);
    }

    /**
     * 시도 한 값을 표준명으로. 아는 값이 아니면 null (호출부가 그 의미를 정한다).
     */
    public static String canonicalProvince(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        if (CANONICAL_PROVINCES.contains(value)) return value;
        String renamed = RENAMED_PROVINCES.get(value);
        if (renamed != null) return renamed;
        String merged = MERGED_PROVINCES.get(value);
        if (merged != null) return merged;
        return SHORT_FORMS.get(value);
    }

    // ---- 내부 ----

    /**
     * 시군구(하위 행정구역) 꼴인지.
     *
     * <p>"시"로 끝난다는 것만으로는 부족하다 — 광역 접미사(특별시·광역시·특별자치시)가 붙은 값은
     * 아직 모르는 <b>시도</b>일 수 있으므로 제외한다. 아는 축약형("서울", "경기")은
     * 호출 전에 이미 표준명으로 해석되므로 여기까지 오지 않는다.
     */
    private static boolean looksLikeDistrict(String value) {
        return DISTRICT_TOKEN.matcher(value).matches()
                && !PROVINCE_SUFFIX.matcher(value).find()
                && !NOT_ADMINISTRATIVE.matcher(value).find();
    }

    private static boolean isValidDistrict(String value) {
        if (NOT_ADMINISTRATIVE.matcher(value).find()) return false;
        return DISTRICT_TOKEN.matcher(value).matches() || CITY_WITH_GU.matcher(value).matches();
    }

    /** 주소 맨 앞에서 시도를 읽는다. 공백이 없는 주소(중복 비교용 표준형)도 접두사로 떼어 본다 */
    private static String provinceFromAddress(String address) {
        if (address == null) return null;
        String head = address.trim().split("\\s+")[0];
        String canonical = canonicalProvince(head);
        if (canonical != null) return canonical;

        String compact = address.replaceAll("\\s+", "");
        for (String prefix : PROVINCE_PREFIXES) {
            if (compact.startsWith(prefix)) return canonicalProvince(prefix);
        }
        return null;
    }

    /**
     * 주소에서 시군구를 읽는다. 시도 토큰을 찾으면 그다음부터, 못 찾으면 맨 앞부터 본다.
     * '○○시' 바로 뒤에 '△△구'가 오면 일반구로 보고 함께 담는다.
     */
    private static String districtFromAddress(String address, String province) {
        if (address == null) return null;
        String[] tokens = address.trim().split("\\s+");
        if (tokens.length == 0) return null;

        int start = 0;
        if (province != null) {
            for (int i = 0; i < tokens.length; i++) {
                if (province.equals(canonicalProvince(tokens[i]))) {
                    start = i + 1;
                    break;
                }
            }
        }
        if (start >= tokens.length) return null;

        String city = tokens[start];
        if (!looksLikeDistrict(city)) return null;

        if (CITY_ONLY.matcher(city).matches() && start + 1 < tokens.length) {
            String next = tokens[start + 1];
            if (GU_TOKEN.matcher(next).matches()) return city + " " + next;
        }
        return city;
    }

    private static List<String> provincePrefixes() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String canonical : CANONICAL_PROVINCES) all.put(canonical, canonical);
        all.putAll(RENAMED_PROVINCES);
        all.putAll(MERGED_PROVINCES);
        // 축약형은 접두사 매칭에 쓰지 않는다. "경기"가 "경기도"를, "전북"이 "전북특별자치도"를 가로채
        // 뒤 토큰을 잘못 끊을 수 있다.
        return all.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
