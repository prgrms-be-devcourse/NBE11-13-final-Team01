package com.zipick.notice.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.MapHousingAttachmentResponse;
import com.zipick.notice.dto.MapHousingDetailResponse;
import com.zipick.notice.dto.MapHousingListResponse;
import com.zipick.notice.dto.MapHousingSummaryResponse;
import com.zipick.notice.dto.MapHousingSupplyResponse;
import com.zipick.notice.dto.MapHousingSupplyConditionResponse;
import com.zipick.notice.dto.MapHousingMediaResponse;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.repository.HousingPinQueryRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.HousingSupplyConditionRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import com.zipick.notice.repository.HousingMediaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MapHousingQueryService {

    private final HousingRepository housingRepository;
    private final HousingPinQueryRepository housingPinQueryRepository;
    private final HousingSupplyRepository housingSupplyRepository;
    private final HousingSupplyConditionRepository housingSupplyConditionRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final HousingMediaRepository housingMediaRepository;

    public MapHousingQueryService(
            HousingRepository housingRepository,
            HousingPinQueryRepository housingPinQueryRepository,
            HousingSupplyRepository housingSupplyRepository,
            HousingSupplyConditionRepository housingSupplyConditionRepository,
            NoticeAttachmentRepository noticeAttachmentRepository,
            HousingMediaRepository housingMediaRepository
    ) {
        this.housingRepository = housingRepository;
        this.housingPinQueryRepository = housingPinQueryRepository;
        this.housingSupplyRepository = housingSupplyRepository;
        this.housingSupplyConditionRepository = housingSupplyConditionRepository;
        this.noticeAttachmentRepository = noticeAttachmentRepository;
        this.housingMediaRepository = housingMediaRepository;
    }

    /**
     * 지도에 찍을 공급주택. 주택 단위로 조회하므로 호실 수와 무관하게 결과가 잘리지 않는다.
     * 필터는 MapHousingFilterPolicy 를 거친 것이어야 한다(상태 교집합 + 공급 조건 엄격 판정).
     */
    public MapHousingListResponse findHousings(HouseFilter filter) {
        var housings = housingPinQueryRepository.findPins(filter).stream()
                .map(MapHousingSummaryResponse::from)
                .toList();
        return new MapHousingListResponse(housings.size(), housings);
    }

    /**
     * 접수 중(OPEN)만 좌표와 함께 돌려주던 이전 조회. 필터·상태 정책을 되돌려야 할 때를 위해 남겨 둔다.
     * 현재 컨트롤러는 findHousings 를 쓴다.
     */
    public MapHousingListResponse findOpenHousings() {
        var housings = housingRepository
                .findAllByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullAndNoticeStatusOrderByIdAsc(NoticeStatus.OPEN)
                .stream()
                .map(MapHousingSummaryResponse::from)
                .toList();
        return new MapHousingListResponse(housings.size(), housings);
    }

    public MapHousingDetailResponse findDetail(Long housingId) {
        var housing = housingRepository.findByIdAndActiveTrue(housingId)
                .filter(candidate -> NoticeStatus.PUBLIC_STATUSES.contains(candidate.getNotice().getStatus()))
                .orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        var supplyEntities = housingSupplyRepository.findAllByHousingIdAndActiveTrueOrderByIdAsc(housingId);
        var conditionMap = supplyEntities.isEmpty()
                ? java.util.Map.<Long, java.util.List<com.zipick.notice.domain.HousingSupplyCondition>>of()
                : housingSupplyConditionRepository.findAllByHousingSupplyIdIn(
                                supplyEntities.stream().map(supply -> supply.getId()).toList()
                        ).stream().collect(java.util.stream.Collectors.groupingBy(
                                condition -> condition.getHousingSupply().getId()
                        ));
        var supplies = supplyEntities.stream().map(supply -> MapHousingSupplyResponse.from(
                supply,
                conditionMap.getOrDefault(supply.getId(), java.util.List.of()).stream()
                        .map(MapHousingSupplyConditionResponse::from).toList()
        )).toList();
        var attachments = noticeAttachmentRepository.findAllByNoticeIdOrderByIdAsc(housing.getNotice().getId())
                .stream().map(MapHousingAttachmentResponse::from).toList();
        var media = housingMediaRepository.findAllByHousingIdOrderByIdAsc(housingId)
                .stream().map(MapHousingMediaResponse::from).toList();
        return MapHousingDetailResponse.of(housing, supplies, attachments, media);
    }
}
