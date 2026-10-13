package com.zipick.notice.service;

import com.zipick.notice.dto.NoticeResponse;

public record NoticeSyncCommand(
        NoticeResponse notice,
        String rawPayload
) {
}
