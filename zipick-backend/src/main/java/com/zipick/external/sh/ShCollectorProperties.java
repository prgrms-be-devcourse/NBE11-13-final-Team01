package com.zipick.external.sh;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zipick.sources.sh")
public record ShCollectorProperties(
        Boolean enabled,
        String listUrl,
        Integer maxPages,
        Integer lookbackDays,
        Long requestDelayMillis
) {
    private static final String DEFAULT_LIST_URL =
            "https://www.i-sh.co.kr/app/lay2/program/S48T1581C563/www/brd/m_247/list.do?multi_itm_seq=2";

    public ShCollectorProperties {
        listUrl = listUrl == null || listUrl.isBlank() ? DEFAULT_LIST_URL : listUrl.trim();
        maxPages = maxPages == null || maxPages < 1 ? 40 : maxPages;
        lookbackDays = lookbackDays == null || lookbackDays < 1 ? 365 : lookbackDays;
        requestDelayMillis = requestDelayMillis == null || requestDelayMillis < 0 ? 100L : requestDelayMillis;
    }

    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }
}
