package com.zipick.notice.dto;

import com.zipick.notice.domain.HousingMedia;

public record MapHousingMediaResponse(
        Long mediaId,
        String mediaType,
        String fileName,
        String sourceUrl
) {
    public static MapHousingMediaResponse from(HousingMedia media) {
        return new MapHousingMediaResponse(
                media.getId(), media.getMediaType(), media.getFileName(), media.getSourceUrl()
        );
    }
}
