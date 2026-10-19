package com.zipick.infrastructure.service;

import com.zipick.global.util.JsonUtil;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.domain.HousingInfraPlace;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.infrastructure.repository.HousingInfraPlaceRepository;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import com.zipick.infrastructure.service.InfraCollectionResult.CollectedCategory;
import com.zipick.infrastructure.service.InfraCollectionResult.NearestPlace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 결과를 저장한다. 주택 한 채가 트랜잭션 하나다.
 * 외부 호출은 이 클래스 밖(Collector)에서 끝내고 들어오므로, 트랜잭션이 HTTP 응답을 기다리지 않는다.
 */
@Service
public class HousingInfraPersistenceService {

    private final HousingInfraSnapshotRepository snapshotRepository;
    private final HousingInfraPlaceRepository placeRepository;
    private final InfraPolicy policy;

    public HousingInfraPersistenceService(
            HousingInfraSnapshotRepository snapshotRepository,
            HousingInfraPlaceRepository placeRepository,
            InfraPolicy policy
    ) {
        this.snapshotRepository = snapshotRepository;
        this.placeRepository = placeRepository;
        this.policy = policy;
    }

    /** 전 카테고리 수집에 성공했을 때만 호출한다. 실패 시에는 기존 캐시를 그대로 둔다. */
    @Transactional
    public void save(
            Long housingId,
            BigDecimal latitude,
            BigDecimal longitude,
            List<CollectedCategory> categories,
            Instant collectedAt
    ) {
        String signature = policy.signature();
        String nearestJson = toNearestJson(categories);

        HousingInfraSnapshot snapshot = snapshotRepository.findByHousingId(housingId).orElse(null);
        if (snapshot == null) {
            snapshot = snapshotRepository.save(new HousingInfraSnapshot(
                    housingId, signature, latitude, longitude, nearestJson, collectedAt
            ));
        } else {
            snapshot.refresh(signature, latitude, longitude, nearestJson, collectedAt);
            // 기존 행을 통째로 지우고 다시 넣는다. 카테고리별 upsert 를 따로 만들 필요가 없다.
            placeRepository.deleteBySnapshotId(snapshot.getId());
        }

        Long snapshotId = snapshot.getId();
        List<HousingInfraPlace> places = categories.stream()
                .filter(CollectedCategory::hasPlace)
                .map(category -> toPlace(snapshotId, category))
                .toList();
        if (!places.isEmpty()) {
            placeRepository.saveAll(places);
        }
    }

    /**
     * 추천(ScoreCalculator)이 기대하는 형식으로 요약을 만든다.
     * key 는 카카오 카테고리 코드, value 는 {"name":..., "distanceM":...} 이다.
     */
    static String toNearestJson(List<CollectedCategory> categories) {
        Map<String, Map<String, Object>> nearest = new LinkedHashMap<>();
        for (CollectedCategory collected : categories) {
            if (!collected.hasPlace()) {
                continue;
            }
            NearestPlace place = collected.nearest();
            // Map.of 는 순서를 보장하지 않아 직렬화 결과가 매번 달라진다. 순서를 고정한다.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", place.name());
            entry.put("distanceM", place.distanceMeter());
            nearest.put(collected.category().kakaoCode(), entry);
        }
        return JsonUtil.write(nearest);
    }

    private static HousingInfraPlace toPlace(Long snapshotId, CollectedCategory collected) {
        NearestPlace place = collected.nearest();
        return HousingInfraPlace.builder()
                .snapshotId(snapshotId)
                .categoryCode(collected.category().code())
                .searchCount(collected.searchCount())
                .name(place.name())
                .distanceMeter(place.distanceMeter())
                .kakaoPlaceId(place.kakaoPlaceId())
                .roadAddress(place.roadAddress())
                .address(place.address())
                .latitude(place.latitude())
                .longitude(place.longitude())
                .placeUrl(place.placeUrl())
                .build();
    }
}
