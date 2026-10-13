package com.zipick.notice.dto;

import java.math.BigDecimal;

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
        String rawPayload
) {
}
