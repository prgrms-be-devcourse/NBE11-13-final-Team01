package com.zipick.infrastructure.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.domain.HousingInfraPlace;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.infrastructure.dto.HousingInfraResponse;
import com.zipick.infrastructure.dto.InfraCategoryResponse;
import com.zipick.infrastructure.dto.InfraStatus;
import com.zipick.infrastructure.repository.HousingInfraPlaceRepository;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주변 인프라 조회. DB 에 저장된 수집 결과만 읽는다.
 * 여기서는 카카오를 호출하지 않으므로 응답이 외부 API 지연에 묶이지 않고, 락·재시도 같은 장치도 필요 없다.
 * 아직 수집되지 않은 주택은 PENDING 으로 내려보내고 배치가 채우기를 기다린다.
 */
@Service
@Transactional(readOnly = true)
public class HousingInfraQueryService {

    private final HousingRepository housingRepository;
    private final HousingInfraSnapshotRepository snapshotRepository;
    private final HousingInfraPlaceRepository placeRepository;
    private final InfraProperties properties;
    private final InfraPolicy policy;

    public HousingInfraQueryService(
            HousingRepository housingRepository,
            HousingInfraSnapshotRepository snapshotRepository,
            HousingInfraPlaceRepository placeRepository,
            InfraProperties properties,
            InfraPolicy policy
    ) {
        this.housingRepository = housingRepository;
        this.snapshotRepository = snapshotRepository;
        this.placeRepository = placeRepository;
        this.properties = properties;
        this.policy = policy;
    }

    public HousingInfraResponse find(Long housingId) {
        // 지도 상세와 같은 노출 기준(active + 모집 중)을 쓴다.
        Housing housing = housingRepository.findByIdAndActiveTrue(housingId)
                .filter(candidate -> candidate.getNotice().getStatus() == NoticeStatus.OPEN)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));

        if (!housing.hasCoordinates()) {
            return HousingInfraResponse.empty(housingId, InfraStatus.UNAVAILABLE);
        }

        HousingInfraSnapshot snapshot = snapshotRepository.findByHousingId(housingId).orElse(null);
        if (snapshot == null) {
            return HousingInfraResponse.empty(housingId, InfraStatus.PENDING);
        }

        Instant ttlThreshold = Instant.now().minus(properties.cacheTtl());
        boolean valid = snapshot.isValid(
                policy.signature(), housing.getLatitude(), housing.getLongitude(), ttlThreshold
        );

        List<HousingInfraPlace> places = placeRepository.findAllBySnapshotIdOrderByIdAsc(snapshot.getId());
        return HousingInfraResponse.of(
                housingId,
                valid ? InfraStatus.FRESH : InfraStatus.STALE,
                snapshot.getCollectedAt(),
                toCategories(places)
        );
    }

    /**
     * 설정에 정의한 카테고리 순서로 정렬한다.
     * 0건 카테고리는 애초에 Place 행이 없으므로 자연히 빠진다.
     */
    private List<InfraCategoryResponse> toCategories(List<HousingInfraPlace> places) {
        Map<String, HousingInfraPlace> placeByCode = places.stream()
                .collect(Collectors.toMap(
                        HousingInfraPlace::getCategoryCode,
                        Function.identity(),
                        (first, second) -> first
                ));

        List<InfraCategoryResponse> categories = new ArrayList<>();
        for (Category category : policy.categories()) {
            HousingInfraPlace place = placeByCode.get(category.code());
            if (place == null) {
                continue;
            }
            categories.add(new InfraCategoryResponse(
                    category.code(),
                    category.label(),
                    category.radiusM(),
                    place.getName(),
                    place.getDistanceMeter(),
                    place.getSearchCount()
            ));
        }
        return List.copyOf(categories);
    }
}
