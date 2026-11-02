package com.zipick.notice.domain;

public enum NoticeStatusReason {
    SOURCE_STATUS,
    DATE_RULE,
    SOURCE_MISSING,
    SOURCE_REPLACED,
    /** 운영자 화면에서 직접 바꿈 */
    ADMIN
}
