package com.zipick.notice.dto;

import com.zipick.notice.domain.HousingSupplyCondition;

public record MapHousingSupplyConditionResponse(
        String conditionCode,
        String conditionName,
        Long rentDeposit,
        Long monthlyRent
) {
    public static MapHousingSupplyConditionResponse from(HousingSupplyCondition condition) {
        return new MapHousingSupplyConditionResponse(
                condition.getConditionCode(), condition.getConditionName(),
                condition.getRentDeposit(), condition.getMonthlyRent()
        );
    }
}
