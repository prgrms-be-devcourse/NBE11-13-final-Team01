package com.zipick.notice.dto;

import com.zipick.notice.domain.HousingSupply;

import java.math.BigDecimal;

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
        Long monthlyRent
) {
    public static MapHousingSupplyResponse from(HousingSupply supply) {
        return new MapHousingSupplyResponse(
                supply.getId(), supply.getHousingType(), supply.getSupplyType(),
                supply.getBuildingDong(), supply.getUnitNumber(), supply.getFloor(),
                supply.getRoomCount(), supply.getExclusiveArea(), supply.getSupplyCount(),
                supply.getSupplyHouseCount(), supply.getRentDeposit(), supply.getMonthlyRent()
        );
    }
}
