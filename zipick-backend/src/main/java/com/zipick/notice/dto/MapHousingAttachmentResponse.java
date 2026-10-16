package com.zipick.notice.dto;

import com.zipick.notice.domain.NoticeAttachment;

public record MapHousingAttachmentResponse(
        Long attachmentId,
        String attachmentType,
        String fileName,
        String sourceUrl
) {
    public static MapHousingAttachmentResponse from(NoticeAttachment attachment) {
        return new MapHousingAttachmentResponse(
                attachment.getId(), attachment.getAttachmentType(),
                attachment.getFileName(), attachment.getSourceUrl()
        );
    }
}
