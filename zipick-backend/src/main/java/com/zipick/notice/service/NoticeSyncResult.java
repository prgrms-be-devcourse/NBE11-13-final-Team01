package com.zipick.notice.service;

public record NoticeSyncResult(
        int createdNotices,
        int updatedNotices,
        int synchronizedHousings,
        int synchronizedSupplies,
        int missingClosedNotices,
        int inactiveHousings,
        int inactiveSupplies
) {
}
