package com.zipick.notice.dto;

import java.util.List;

public record NoticeHousingResponse(
        String sourceHousingKey,
        String complexName,
        String provinceName,
        String districtName,
        String fullAddress,
        String lotAddress,
        String roadAddress,
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

    public NoticeHousingResponse(
            String sourceHousingKey, String complexName, String provinceName, String districtName,
            String fullAddress, String roadName, String legalDongName, String pnu,
            String heatingMethodName, String totalHouseholdCount,
            List<NoticeSupplyResponse> supplies, List<HousingMediaResponse> media
    ) {
        this(sourceHousingKey, complexName, provinceName, districtName, fullAddress,
                null, null, roadName, legalDongName, pnu, heatingMethodName,
                totalHouseholdCount, supplies, media);
    }
}
