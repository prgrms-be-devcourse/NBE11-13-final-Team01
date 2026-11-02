package com.zipick.notice.support;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AddressNormalizer {

    private static final Pattern PARENTHESIZED = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern ROAD_NAME = Pattern.compile("^[가-힣0-9·.]+(?:대로|로|길)(?:\\d+번길)?$");
    private static final Pattern LEGAL_DONG = Pattern.compile(".*(?:동|읍|면|리|가)$");
    private static final Pattern PLACEHOLDER_ADDRESS = Pattern.compile(
            ".*(?:^|\\s)0{2,}(?:구|동|읍|면|로|길)?(?:\\s|$).*"
    );

    private AddressNormalizer() {
    }

    /**
     * 사람이 확인할 원문 주소는 건드리지 않고, 중복 비교에 쓸 주소만 표준화한다.
     *
     * <p>시도 개칭은 {@link RegionNormalizer#RENAMED_PROVINCES} 한 곳에서 관리한다(표를 두 군데 두지 않는다).
     * 그 표에는 <b>가리키는 범위가 완전히 같은 1:1 개칭만</b> 들어 있다.
     *
     * <p>⚠️ 행정구역 통합(광주광역시·전라남도 → 전남광주통합특별시)은 여기에 적용하지 않는다.
     * 이 결과가 {@code source_housing_key}의 재료라서, 치환 규칙을 바꾸면 다음 수집에서 같은 주택이
     * 다른 키로 보여 중복 행이 생긴다. 지역 컬럼의 통합 반영은 {@code RegionNormalizer}가 따로 한다.
     */
    public static String canonicalize(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String normalized = collapseRepeatedSegments(address.trim());
        for (var rename : RegionNormalizer.RENAMED_PROVINCES.entrySet()) {
            normalized = normalized.replace(rename.getKey(), rename.getValue());
        }
        normalized = normalized
                .replaceAll("\\([^()]*\\)", "")
                .replaceAll("[\\s,]+", "")
                .toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    private static String collapseRepeatedSegments(String address) {
        java.util.List<String> tokens = new java.util.ArrayList<>(java.util.List.of(
                address.replaceAll("\\s+", " ").split(" ")
        ));
        boolean changed;
        do {
            changed = false;
            for (int size = Math.min(4, tokens.size() / 2); size >= 1 && !changed; size--) {
                for (int start = 0; start + size * 2 <= tokens.size(); start++) {
                    if (tokens.subList(start, start + size)
                            .equals(tokens.subList(start + size, start + size * 2))) {
                        tokens.subList(start + size, start + size * 2).clear();
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return String.join(" ", tokens);
    }

    /**
     * 공고문 양식에 들어 있는 예시 주소를 실제 공급주택으로 저장하지 않기 위한 검사다.
     */
    public static boolean isPlaceholder(String address) {
        if (!StringUtils.hasText(address)) {
            return true;
        }
        String normalized = address.trim().replaceAll("\\s+", " ");
        return normalized.contains("00구")
                || normalized.contains("0000로")
                || normalized.contains("0000길")
                || PLACEHOLDER_ADDRESS.matcher(normalized).matches();
    }

    public static String extractRoadName(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String withoutParentheses = address.replaceAll("\\([^()]*\\)", " ");
        for (String token : withoutParentheses.trim().split("\\s+")) {
            if (ROAD_NAME.matcher(token).matches()) {
                return token;
            }
        }
        return null;
    }

    public static String extractLegalDongName(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        Matcher matcher = PARENTHESIZED.matcher(address);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim().split(",")[0].trim();
            if (LEGAL_DONG.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return null;
    }

    public static String extractBuildingName(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        Matcher matcher = PARENTHESIZED.matcher(address);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim().split(",")[0].trim();
            if (StringUtils.hasText(candidate) && !LEGAL_DONG.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return null;
    }
}
