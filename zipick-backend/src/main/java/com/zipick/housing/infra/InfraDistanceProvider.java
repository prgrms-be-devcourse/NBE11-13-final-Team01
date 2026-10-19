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
     * 반환 Map 은 세 가지 상태를 구분한다. 인프라 점수를 정확히 매기려면 이 구분이 필요하다.
     *   1) 주택 id 가 없음        → 수집 전이거나 쓸 수 없는 데이터. 인프라 점수를 계산하지 않는다(가중치 재분배)
     *   2) key 는 있고 값이 null  → 조회는 했지만 반경 안에 그 시설이 없다. 0점으로 센다
     *   3) key 와 거리 값이 있음  → 그 거리로 점수를 매긴다
     * 즉 수집한 주택은 수집 대상 카테고리를 **전부** key 로 담는다.
     *
     * @return 주택 id → (카카오 카테고리 코드 → 최단 거리 m). 예: {12: {"SW8": 420, "CS2": null}}
     */
    Map<Long, Map<String, Integer>> nearest(Collection<Candidate> candidates);
}
