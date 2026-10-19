package com.zipick.housing.infra;

import com.zipick.housing.domain.Candidate;
import java.util.Collection;
import java.util.Map;

/**
 * 주변 시설까지의 최단 거리. 추천의 인프라 점수가 이 값을 쓴다.
 *
 * 주변 인프라 담당이 실제 구현(카카오 로컬 검색 등)을 @Primary 빈으로 등록하면 그 값을 쓴다.
 * 구현이 없으면 NoInfraDistanceProvider 가 빈 값을 돌려주고, 인프라 항목은 '정보 없음'으로 계산에서 빠진다.
 */
public interface InfraDistanceProvider {

    /**
     * @return 주택 id → (카카오 카테고리 코드 → 최단 거리 m). 예: {12: {"SW8": 420, "CS2": 120}}
     */
    Map<Long, Map<String, Integer>> nearest(Collection<Candidate> candidates);
}
