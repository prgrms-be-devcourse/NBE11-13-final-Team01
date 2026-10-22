package com.zipick.external.gh;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zipick.sources.gh")
public record GhCollectorProperties(
        Boolean enabled,
        Integer maxPages,
        Long requestDelayMillis
) {
    public GhCollectorProperties {
        maxPages = maxPages == null || maxPages < 1 ? 5 : maxPages;
        requestDelayMillis = requestDelayMillis == null || requestDelayMillis < 0 ? 100L : requestDelayMillis;
    }

    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }
}
