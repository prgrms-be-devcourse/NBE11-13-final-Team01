package com.zipick.notice.service;

import org.springframework.util.StringUtils;

public final class RentalNoticePolicy {

    private RentalNoticePolicy() {
    }

    public static boolean isExcluded(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.replaceAll("\\s+", "")
                    .replace("비분양전환", "");
            if (normalized.contains("분양전환")
                    || normalized.contains("공공분양")
                    || normalized.contains("분양주택")
                    || normalized.contains("일반매각")) {
                return true;
            }
        }
        return false;
    }
}
