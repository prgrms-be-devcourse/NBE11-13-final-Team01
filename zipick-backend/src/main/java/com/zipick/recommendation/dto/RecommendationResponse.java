package com.zipick.recommendation.dto;

import java.util.List;
import java.util.Map;

/**
 * 추천 목록 응답.
 *
 * @param profileId      활성 추천 기준 ID (없으면 null, 기본 가중치 사용)
 * @param profileVersion 추천 기준 버전
 * @param baseWeights    항목별 기본 가중치 (설정값)
 * @param priorities     사용자 우선순위 1~5
 * @param total          기준 점수를 넘은 집 수 (공고당 제한 전)
 * @param candidateTotal 필터를 통과한 후보 수 (점수 계산 대상)
 * @param excluded       지원조건·MUST 규칙으로 제외된 수
 * @param houses         추천 결과 (최대 maxResults)
 */
public record RecommendationResponse(
        Long profileId,
        Integer profileVersion,
        Map<String, Integer> baseWeights,
        Map<String, Integer> priorities,
        int total,
        int candidateTotal,
        int excluded,
        List<RecommendedHouseResponse> houses
) {}
