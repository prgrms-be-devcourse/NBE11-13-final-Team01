package com.zipick.notice.domain;

import java.util.Set;

public enum NoticeStatus {
    UPCOMING,
    OPEN,
    CLOSED,
    CANCELLED;

    /**
     * 비로그인으로도 공개하는 모집 상태. 지도 핀 목록·공개 상세·이미지 조회가 모두 이 집합을 쓴다.
     * 한곳에서 관리해야 "목록에는 보이는데 상세는 404" 같은 어긋남이 생기지 않는다.
     * 접수 마감·취소 공고는 제외한다.
     * 추천 후보 범위(HouseFilter.OPEN_STATUSES)와 같은 집합이지만, notice 패키지가
     * housing 패키지를 참조하지 않도록(의존 방향이 housing → notice 한쪽이다) 여기에 따로 둔다.
     * 두 값이 어긋나면 목록에는 보이는데 상세는 404가 나므로 함께 맞춰야 한다.
     */
    public static final Set<NoticeStatus> PUBLIC_STATUSES = Set.of(UPCOMING, OPEN);
}
