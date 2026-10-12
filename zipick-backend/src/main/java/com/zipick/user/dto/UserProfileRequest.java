package com.zipick.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 기본 정보 저장 요청. 금액은 만 원. */
public record UserProfileRequest(
        @NotNull @Past LocalDate birthDate,
        @Size(max = 30) String job,
        @NotNull Boolean married,
        @NotNull @Min(1) @Max(10) Integer householdSize,
        @Min(0) Integer monthlyIncome,
        @Min(0) Integer totalAssets,
        @Min(0) Integer carValue,
        @NotNull Boolean homeless
) {}
