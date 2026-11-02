package com.zipick.applicationguide.dto;

/**
 * 가이드 조회·생성 결과 상태.
 *
 * 생성 실패를 HTTP 오류로 내리지 않고 이 상태로 내리는 이유는, 주변 인프라 조회
 * (HousingInfraQueryService 의 FRESH / STALE / PENDING / UNAVAILABLE)와 같은 이유다.
 * "만들지 못했다"는 장애가 아니라 화면이 그려야 할 정상적인 상태이고,
 * AI 실패가 관심 공고·지도·일정 화면을 깨뜨려서는 안 된다.
 */
public enum GuideStatus {
    /** 저장된 가이드가 있다 */
    READY,
    /** 아직 만든 적이 없다. 사용자가 버튼을 눌러야 생성된다 (GET 응답에서만 나온다) */
    NOT_GENERATED,
    /** 만들 수 없었다. reason 에 이유가 담긴다 */
    UNAVAILABLE
}
