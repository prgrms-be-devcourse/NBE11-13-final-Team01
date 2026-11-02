package com.zipick.notice.dto;

public record HousingMediaResponse(
        String sourceKey,
        String mediaType,
        String fileName,
        String sourceUrl,
        String rawPayload
) {
}
