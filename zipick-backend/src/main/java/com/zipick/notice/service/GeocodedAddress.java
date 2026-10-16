package com.zipick.notice.service;

import java.math.BigDecimal;

public record GeocodedAddress(
        String normalizedAddress,
        String provinceName,
        String districtName,
        String legalDongName,
        String roadName,
        String buildingName,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
