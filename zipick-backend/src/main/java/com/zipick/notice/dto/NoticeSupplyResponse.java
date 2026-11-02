package com.zipick.notice.dto;

import java.math.BigDecimal;
import java.util.List;

public record NoticeSupplyResponse(
        String sourceSupplyKey,
        Long externalHouseId,
        String houseTypeName,
        String supplyTypeName,
        String buildingDong,
        String unitNumber,
        Integer floor,
        Integer roomCount,
        BigDecimal exclusiveArea,
        Long supplyCount,
        String supplyHouseCount,
        Long rentDeposit,
        Long contractDeposit,
        Long interimPayment,
        Long balance,
        Long monthlyRent,
        String rawPayload,
        Integer sourceRowNumber,
        String supplyStatus,
        String nearbySubwayStation,
        BigDecimal subwayWalkDistanceKm,
        Boolean elevatorAvailable,
        Boolean communityFacilityAvailable,
        Integer totalParkingSpaces,
        Boolean mechanicalParkingAvailable,
        Integer mechanicalParkingSpaces,
        List<NoticeSupplyConditionResponse> rentalConditions
) {
    public NoticeSupplyResponse {
        rentalConditions = rentalConditions == null ? List.of() : List.copyOf(rentalConditions);
    }

    public NoticeSupplyResponse(
            String sourceSupplyKey, Long externalHouseId, String houseTypeName,
            String supplyTypeName, String buildingDong, String unitNumber, Integer floor,
            Integer roomCount, BigDecimal exclusiveArea, Long supplyCount,
            String supplyHouseCount, Long rentDeposit, Long contractDeposit,
            Long interimPayment, Long balance, Long monthlyRent, String rawPayload
    ) {
        this(sourceSupplyKey, externalHouseId, houseTypeName, supplyTypeName, buildingDong,
                unitNumber, floor, roomCount, exclusiveArea, supplyCount, supplyHouseCount,
                rentDeposit, contractDeposit, interimPayment, balance, monthlyRent, rawPayload,
                null, null, null, null, null, null, null, null, null, List.of());
    }
}
