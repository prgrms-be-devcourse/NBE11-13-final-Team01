package com.zipick.user.dto;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 희망 주거조건 저장 요청. 금액은 만 원, 면적은 ㎡. */
public record UserPreferenceRequest(
        @NotEmpty @Size(max = 3) @Valid List<Region> regions,
        @Min(0) Integer depositMax,
        @Min(0) Integer rentMax,
        BigDecimal areaMin,
        BigDecimal areaMax,
        List<HousingType> housingTypes,
        List<RentalType> rentalTypes,
        @Min(0) Integer monthlyBudget,
        @Min(1) @Max(10) Integer roomsMin
) {
    /** code = "시도 시군구"(예: "경상북도 구미시"), name = 화면 표시용 */
    public record Region(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull @Min(1) @Max(3) Integer rank
    ) {}
}
