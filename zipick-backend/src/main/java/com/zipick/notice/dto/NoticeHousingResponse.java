package com.zipick.notice.dto;

import java.util.List;

public record NoticeHousingResponse(
        String sourceHousingKey,
        String complexName,
        String provinceName,
        String districtName,
        String fullAddress,
        String roadName,
        String legalDongName,
        String pnu,
        String heatingMethodName,
        String totalHouseholdCount,
        List<NoticeSupplyResponse> supplies,
        List<HousingMediaResponse> media
) {
    public NoticeHousingResponse {
        supplies = supplies == null ? List.of() : List.copyOf(supplies);
        media = media == null ? List.of() : List.copyOf(media);
    }
}
