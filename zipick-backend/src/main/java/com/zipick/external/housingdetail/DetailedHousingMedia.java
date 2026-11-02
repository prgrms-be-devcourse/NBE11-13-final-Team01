package com.zipick.external.housingdetail;

public record DetailedHousingMedia(
        String complexKey,
        String sourceKey,
        String mediaType,
        String fileName,
        String sourceUrl,
        String rawPayload
) {
}
