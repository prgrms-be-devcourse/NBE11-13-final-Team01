package com.zipick.recommendation.dto;

/**
 * 다음에 비교할 두 집. 더 물어볼 쌍이 없으면 left/right 가 null.
 * 화면에서는 점수를 가리고 집 정보만 보여 줘야 선택이 점수에 끌려가지 않는다.
 */
public record PairQuestionResponse(
        RecommendedHouseResponse left,
        RecommendedHouseResponse right,
        int answered,
        int minRequired
) {}
