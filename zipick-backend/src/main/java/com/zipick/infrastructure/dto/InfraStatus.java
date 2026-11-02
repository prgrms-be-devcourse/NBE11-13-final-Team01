package com.zipick.infrastructure.dto;

/** 주변 인프라 정보의 제공 상태. 수집 성공 0건과 아직 수집되지 않은 상태를 섞지 않기 위해 나눈다. */
public enum InfraStatus {

    /** 유효한 수집 결과. categories 가 비어 있으면 '반경 안에 시설이 없음'을 뜻한다. */
    FRESH,

    /** 이전 수집 결과는 있으나 TTL·정책·좌표 변경으로 갱신 대기 중이다. 값은 그대로 보여 준다. */
    STALE,

    /** 좌표는 있는데 아직 수집되지 않았다. 다음 배치 주기에 채워진다. */
    PENDING,

    /** 좌표가 없어 주변 인프라를 제공할 수 없다. */
    UNAVAILABLE
}
