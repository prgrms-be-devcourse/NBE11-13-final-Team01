package com.zipick.notice.dto;

public record NoticeSupplyConditionResponse(
        String conditionCode,
        String conditionName,
        Long rentDeposit,
        Long monthlyRent,
        String rawPayload
) {
}
