package com.zipick.user.dto;

import tools.jackson.core.type.TypeReference;
import com.zipick.global.util.JsonUtil;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.user.domain.UserPreference;
import java.math.BigDecimal;
import java.util.List;

public record UserPreferenceResponse(
        List<UserPreferenceRequest.Region> regions,
        Integer depositMax,
        Integer rentMax,
        BigDecimal areaMin,
        BigDecimal areaMax,
        List<HousingType> housingTypes,
        List<RentalType> rentalTypes,
        Integer monthlyBudget,
        Integer roomsMin
) {
    public static UserPreferenceResponse from(UserPreference p) {
        return new UserPreferenceResponse(
                JsonUtil.read(p.getRegions(), new TypeReference<List<UserPreferenceRequest.Region>>() {}, List.of()),
                p.getDepositMax(), p.getRentMax(),
                p.getAreaMin(), p.getAreaMax(),
                JsonUtil.read(p.getHousingTypes(), new TypeReference<List<HousingType>>() {}, List.of()),
                JsonUtil.read(p.getRentalTypes(), new TypeReference<List<RentalType>>() {}, List.of()),
                p.getMonthlyBudget(), p.getRoomsMin());
    }
}
