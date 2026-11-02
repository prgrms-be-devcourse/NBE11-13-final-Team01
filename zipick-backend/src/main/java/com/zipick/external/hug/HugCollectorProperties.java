package com.zipick.external.hug;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zipick.sources.hug")
public record HugCollectorProperties(
        Boolean enabled,
        String listUrl,
        Integer maxPages,
        Long requestDelayMillis
) {
    private static final String DEFAULT_LIST_URL =
            "https://www.khug.or.kr/jeonse/web/s07/s070102.jsp?view_Count=Y&CMB_SIDO=ALL"
                    + "&sbGugun=ALL&BJAMT=ALL&BJAREA=ALL&BJORDER=ALL";

    public HugCollectorProperties {
        listUrl = listUrl == null || listUrl.isBlank() ? DEFAULT_LIST_URL : listUrl.trim();
        maxPages = maxPages == null || maxPages < 1 ? 20 : maxPages;
        requestDelayMillis = requestDelayMillis == null || requestDelayMillis < 0 ? 100L : requestDelayMillis;
    }

    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }
}
