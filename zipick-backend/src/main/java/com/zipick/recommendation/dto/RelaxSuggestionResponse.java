package com.zipick.recommendation.dto;

import java.util.List;

/** 추천이 적을 때 조건 완화 제안. */
public record RelaxSuggestionResponse(int currentCount, List<Suggestion> suggestions) {

    /**
     * @param field      바꿀 조건 (rentMax, depositMax, area, housingTypes, regionRule)
     * @param change     사람이 읽는 설명
     * @param countAfter 바꾸면 추천이 몇 곳이 되는지
     */
    public record Suggestion(String field, String change, int countAfter) {}
}
