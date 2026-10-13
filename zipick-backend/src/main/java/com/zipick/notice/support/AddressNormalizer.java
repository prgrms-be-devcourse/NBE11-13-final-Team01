package com.zipick.notice.support;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AddressNormalizer {

    private static final Pattern PARENTHESIZED = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern ROAD_NAME = Pattern.compile("^[가-힣0-9·.]+(?:대로|로|길)(?:\\d+번길)?$");
    private static final Pattern LEGAL_DONG = Pattern.compile(".*(?:동|읍|면|리|가)$");

    private AddressNormalizer() {
    }

    /**
     * 사람이 확인할 원문 주소는 건드리지 않고, 중복 비교에 쓸 주소만 표준화한다.
     */
    public static String canonicalize(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String normalized = address.trim()
                .replace("강원도", "강원특별자치도")
                .replace("전라북도", "전북특별자치도")
                .replace("제주도", "제주특별자치도")
                .replaceAll("\\([^()]*\\)", "")
                .replaceAll("[\\s,]+", "")
                .toLowerCase();
        return normalized.isBlank() ? null : normalized;
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
