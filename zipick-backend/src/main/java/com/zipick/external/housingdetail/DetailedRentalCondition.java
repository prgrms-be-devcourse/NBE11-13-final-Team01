package com.zipick.external.housingdetail;

public record DetailedRentalCondition(
        String conditionCode,
        String conditionName,
        Long rentDeposit,
        Long monthlyRent,
        String rawPayload
) {
}
