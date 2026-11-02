package com.zipick.notice.dto;

import java.util.List;

public record NoticePageResponse(
        int pageNo,
        int numOfRows,
        int totalCount,
        int totalPages,
        List<NoticeResponse> items
) {
    public NoticePageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
