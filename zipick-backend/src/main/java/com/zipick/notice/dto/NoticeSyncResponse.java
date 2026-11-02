package com.zipick.notice.dto;

import com.zipick.notice.service.NoticeSyncResult;

import java.time.Instant;

public record NoticeSyncResponse(
        int collectedRows,
        int createdNotices,
        int updatedNotices,
        int synchronizedHousings,
        int synchronizedSupplies,
        int missingClosedNotices,
        int inactiveHousings,
        int inactiveSupplies,
        Instant startedAt,
        Instant finishedAt
) {
    public static NoticeSyncResponse from(
            int collectedRows,
            NoticeSyncResult result,
            Instant startedAt,
            Instant finishedAt
    ) {
        return new NoticeSyncResponse(
                collectedRows,
                result.createdNotices(),
                result.updatedNotices(),
                result.synchronizedHousings(),
                result.synchronizedSupplies(),
                result.missingClosedNotices(),
                result.inactiveHousings(),
                result.inactiveSupplies(),
                startedAt,
                finishedAt
        );
    }
}
