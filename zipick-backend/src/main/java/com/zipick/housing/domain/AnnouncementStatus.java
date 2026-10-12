package com.zipick.housing.domain;

/** 모집 상태. 정정공고중은 NOTICE로 묶는다. */
public enum AnnouncementStatus {
    NOTICE,     // 공고중(정정공고중 포함)
    RECEIVING,  // 접수중
    CLOSED      // 접수마감
}
