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
     */
    public static String canonicalize(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String normalized = collapseRepeatedSegments(address.trim())
                .replace("강원도", "강원특별자치도")
                .replace("전라북도", "전북특별자치도")
                .replace("제주도", "제주특별자치도")
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
