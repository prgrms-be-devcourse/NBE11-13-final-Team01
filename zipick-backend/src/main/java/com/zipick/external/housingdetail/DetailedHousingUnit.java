package com.zipick.external.housingdetail;

import java.math.BigDecimal;

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
        String rawPayload
) {
}
