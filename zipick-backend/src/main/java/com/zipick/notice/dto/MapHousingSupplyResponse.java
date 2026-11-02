package com.zipick.notice.dto;

import com.zipick.notice.domain.HousingSupply;

import java.math.BigDecimal;
import java.util.List;

public record MapHousingSupplyResponse(
        Long housingSupplyId,
        String housingType,
        String supplyType,
        String buildingDong,
        String unitNumber,
        Integer floor,
        Integer roomCount,
        BigDecimal exclusiveArea,
        Integer supplyCount,
        Integer supplyHouseCount,
        Long rentDeposit,
        Long monthlyRent,
        Integer sourceRowNumber,
        String supplyStatus,
        String nearbySubwayStation,
        BigDecimal subwayWalkDistanceKm,
        Boolean elevatorAvailable,
        Boolean communityFacilityAvailable,
        Integer totalParkingSpaces,
        Boolean mechanicalParkingAvailable,
        Integer mechanicalParkingSpaces,
        List<MapHousingSupplyConditionResponse> rentalConditions
) {
    public MapHousingSupplyResponse {
        rentalConditions = rentalConditions == null ? List.of() : List.copyOf(rentalConditions);
    }

    public static MapHousingSupplyResponse from(
            HousingSupply supply,
            List<MapHousingSupplyConditionResponse> rentalConditions
    ) {
        return new MapHousingSupplyResponse(
                supply.getId(), supply.getHousingType(), supply.getSupplyType(),
                supply.getBuildingDong(), supply.getUnitNumber(), supply.getFloor(),
                supply.getRoomCount(), supply.getExclusiveArea(), supply.getSupplyCount(),
                supply.getSupplyHouseCount(), supply.getRentDeposit(), supply.getMonthlyRent(),
                supply.getSourceRowNumber(), supply.getSupplyStatus(), supply.getNearbySubwayStation(),
                supply.getSubwayWalkDistanceKm(), supply.getElevatorAvailable(),
                supply.getCommunityFacilityAvailable(), supply.getTotalParkingSpaces(),
                supply.getMechanicalParkingAvailable(), supply.getMechanicalParkingSpaces(), rentalConditions
        );
    }
}
