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
    private final HousingSupplyRepository housingSupplyRepository;
    private final HousingSupplyConditionRepository housingSupplyConditionRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final HousingMediaRepository housingMediaRepository;

    public MapHousingQueryService(
            HousingRepository housingRepository,
            HousingSupplyRepository housingSupplyRepository,
            HousingSupplyConditionRepository housingSupplyConditionRepository,
            NoticeAttachmentRepository noticeAttachmentRepository,
            HousingMediaRepository housingMediaRepository
    ) {
        this.housingRepository = housingRepository;
        this.housingSupplyRepository = housingSupplyRepository;
        this.housingSupplyConditionRepository = housingSupplyConditionRepository;
        this.noticeAttachmentRepository = noticeAttachmentRepository;
        this.housingMediaRepository = housingMediaRepository;
    }

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
                .filter(candidate -> candidate.getNotice().getStatus() == NoticeStatus.OPEN)
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
