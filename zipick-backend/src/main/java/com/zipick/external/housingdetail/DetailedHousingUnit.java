package com.zipick.external.housingdetail;

import java.math.BigDecimal;
import java.util.List;

public record DetailedHousingUnit(
        String sourceUnitKey,
        String complexName,
        String provinceName,
        String districtName,
        String address,
        String buildingDong,
        String unitNumber,
        Integer floor,
        BigDecimal exclusiveArea,
        Integer roomCount,
        String housingType,
        String supplyType,
        Long rentDeposit,
        Long monthlyRent,
        String rawPayload,
        String lotAddress,
        String roadAddress,
        Integer sourceRowNumber,
        String supplyStatus,
        String nearbySubwayStation,
        BigDecimal subwayWalkDistanceKm,
        Boolean elevatorAvailable,
        Boolean communityFacilityAvailable,
        Integer totalParkingSpaces,
        Boolean mechanicalParkingAvailable,
        Integer mechanicalParkingSpaces,
        Long supplyCount,
        List<DetailedRentalCondition> rentalConditions
) {
    public DetailedHousingUnit {
        rentalConditions = rentalConditions == null ? List.of() : List.copyOf(rentalConditions);
    }

    public DetailedHousingUnit(
            String sourceUnitKey, String complexName, String provinceName, String districtName,
            String address, String buildingDong, String unitNumber, Integer floor,
            BigDecimal exclusiveArea, Integer roomCount, String housingType, String supplyType,
            Long rentDeposit, Long monthlyRent, String rawPayload
    ) {
        this(sourceUnitKey, complexName, provinceName, districtName, address, buildingDong,
                unitNumber, floor, exclusiveArea, roomCount, housingType, supplyType,
                rentDeposit, monthlyRent, rawPayload, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of());
    }

    public DetailedHousingUnit(
            String sourceUnitKey, String complexName, String provinceName, String districtName,
            String address, String buildingDong, String unitNumber, Integer floor,
            BigDecimal exclusiveArea, Integer roomCount, String housingType, String supplyType,
            Long rentDeposit, Long monthlyRent, String rawPayload, String lotAddress,
            String roadAddress, Integer sourceRowNumber, String supplyStatus,
            String nearbySubwayStation, BigDecimal subwayWalkDistanceKm,
            Boolean elevatorAvailable, Boolean communityFacilityAvailable,
            Integer totalParkingSpaces, Boolean mechanicalParkingAvailable,
            Integer mechanicalParkingSpaces, List<DetailedRentalCondition> rentalConditions
    ) {
        this(sourceUnitKey, complexName, provinceName, districtName, address, buildingDong,
                unitNumber, floor, exclusiveArea, roomCount, housingType, supplyType,
                rentDeposit, monthlyRent, rawPayload, lotAddress, roadAddress, sourceRowNumber,
                supplyStatus, nearbySubwayStation, subwayWalkDistanceKm, elevatorAvailable,
                communityFacilityAvailable, totalParkingSpaces, mechanicalParkingAvailable,
                mechanicalParkingSpaces, null, rentalConditions);
    }
}
