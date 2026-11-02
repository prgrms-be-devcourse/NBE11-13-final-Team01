package com.zipick.infrastructure.service;

import tools.jackson.core.type.TypeReference;
import com.zipick.global.util.JsonUtil;
import com.zipick.housing.domain.Candidate;
import com.zipick.housing.infra.InfraDistanceProvider;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 추천의 인프라 점수에 쓸 거리 정보를 DB 스냅샷에서 공급한다.
 *
 * 배치가 미리 수집해 둔 값만 읽으므로 추천 요청 중에 카카오를 호출하지 않는다.
 * 이 빈이 @Primary 로 등록되면 추천은 NoInfraDistanceProvider(빈 값) 대신 이쪽을 쓴다.
 * 추천 코드는 수정하지 않았고, 확장 지점(InfraDistanceProvider)만 구현했다.
 */
@Primary
@Component
public class SnapshotInfraDistanceProvider implements InfraDistanceProvider {

    private static final TypeReference<Map<String, Map<String, Object>>> NEAREST_TYPE =
            new TypeReference<>() {
            };
    private static final String DISTANCE_KEY = "distanceM";

    private final HousingInfraSnapshotRepository snapshotRepository;
    private final InfraPolicy policy;

    public SnapshotInfraDistanceProvider(
            HousingInfraSnapshotRepository snapshotRepository,
            InfraPolicy policy
    ) {
        this.snapshotRepository = snapshotRepository;
        this.policy = policy;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Integer>> nearest(Collection<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }

        // 후보는 공급 호실 단위라 같은 주택이 여러 번 나온다. 주택 단위로 줄여 한 번만 조회한다.
        // 좌표는 후보가 이미 들고 있으므로 housing 을 다시 읽지 않는다.
        Map<Long, Candidate> byHousingId = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.housingId() != null) {
                byHousingId.putIfAbsent(candidate.housingId(), candidate);
            }
        }
        if (byHousingId.isEmpty()) {
            return Map.of();
        }

        List<HousingInfraSnapshot> snapshots =
                snapshotRepository.findAllByHousingIdIn(byHousingId.keySet());

        Map<Long, Map<String, Integer>> result = new LinkedHashMap<>();
        for (HousingInfraSnapshot snapshot : snapshots) {
            Candidate candidate = byHousingId.get(snapshot.getHousingId());
            if (candidate == null || !isUsable(snapshot, candidate)) {
                continue;
            }
            // 수집한 주택은 카테고리 6종 key 를 모두 넣는다. 반경 내 0건인 카테고리는 값이 null 이다.
            // 아직 수집하지 않은 주택은 아예 key 가 없어서, 추천이 '계산 불가'와 '0건'을 구분할 수 있다.
            result.put(snapshot.getHousingId(), toDistances(snapshot.getNearest(), policy.categories()));
        }
        return Map.copyOf(result);
    }

    /**
     * TTL 만료만으로는 제외하지 않는다. 시설 위치는 잘 바뀌지 않아 오래된 값도 쓸모가 있다.
     * 반면 수집 정책(카테고리·반경)이나 주택 좌표가 바뀌었다면 저장된 거리가 지금 기준과 맞지 않으므로 뺀다.
     */
    private boolean isUsable(HousingInfraSnapshot snapshot, Candidate candidate) {
        return snapshot.matchesCollectionContext(policy.signature(), candidate.lat(), candidate.lng());
    }

    /**
     * {"SW8":{"name":"신설동역","distanceM":683}} → {"SW8":683, "MT1":null, ...}
     *
     * 수집 대상 카테고리를 **모두** key 로 넣는다. 반경 안에 시설이 없었던 카테고리는 값이 null 이다.
     * 추천은 "key 가 있는데 값이 null" 을 0점으로 세고, 주택 자체가 없으면 계산에서 뺀다.
     * key 를 빼 버리면 둘을 구분할 수 없어 '주변에 아무것도 없는 집'이 감점되지 않는다.
     */
    static Map<String, Integer> toDistances(String nearestJson, List<Category> categories) {
        Map<String, Map<String, Object>> parsed = JsonUtil.read(nearestJson, NEAREST_TYPE, Map.of());
        Map<String, Integer> distances = new LinkedHashMap<>();
        for (Category category : categories) {
            // null 값을 담아야 해서 Map.of / Map.copyOf 를 쓸 수 없다.
            distances.put(category.kakaoCode(), toDistance(parsed.get(category.kakaoCode())));
        }
        return Collections.unmodifiableMap(distances);
    }

    private static Integer toDistance(Map<String, Object> place) {
        if (place == null) {
            return null;
        }
        Object value = place.get(DISTANCE_KEY);
        if (!(value instanceof Number number)) {
            return null;
        }
        int distance = number.intValue();
        return distance < 0 ? null : distance;
    }
}
