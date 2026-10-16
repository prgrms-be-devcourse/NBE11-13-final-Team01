package com.zipick.notice.dto;

public record NoticeAttachmentResponse(
        String sourceKey,
        String attachmentType,
        String fileName,
        String sourceUrl,
        String rawPayload
) {
}
