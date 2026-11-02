package com.zipick.recommendation.dto;

import java.util.List;
import java.util.Map;

/**
 * 추천 목록 응답. 결과는 두 묶음이다(설계 결정 D안).
 *
 * @param profileId      활성 추천 기준 ID (없으면 null, 기본 가중치 사용)
 * @param profileVersion 추천 기준 버전
 * @param baseWeights    항목별 기본 가중치 (설정값). 키 순서 = 화면에 보일 항목 순서
 * @param priorities     사용자 우선순위 1~5
 * @param total          순위 묶음에서 기준 점수를 넘은 주택 수 (공고당 제한 전)
 * @param candidateTotal 필터를 통과한 주택 수 (점수 계산 대상)
 * @param excluded       지원조건·MUST 규칙으로 제외된 주택 수
 * @param houses         순위 묶음: 비용·지역 정보가 있어 점수로 비교한 결과 (최대 maxResults)
 * @param incomplete     정보 부족 묶음: 비용·지역 정보가 없어 점수로 비교하지 않은 주택. 마감 임박순
 */
public record RecommendationResponse(
        Long profileId,
        Integer profileVersion,
        Map<String, Integer> baseWeights,
        Map<String, Integer> priorities,
        int total,
        int candidateTotal,
        int excluded,
        List<RecommendedHouseResponse> houses,
        List<RecommendedHouseResponse> incomplete
) {}
