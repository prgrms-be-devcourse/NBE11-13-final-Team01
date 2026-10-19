package com.zipick.user.dto;

import com.zipick.user.domain.StudentStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;

/**
 * 기본 정보 저장 요청. 금액은 만 원. 모든 값은 지원조건 판정에 쓰인다 (docs/recommendation-fields.md).
 * 소득·자산·자동차는 비워도 되지만 비우면 그 조건은 '확인 필요'가 된다.
 */
public record UserProfileRequest(
        @NotNull @Past LocalDate birthDate,
        @NotNull Boolean married,
        @NotNull @Min(1) @Max(10) Integer householdSize,
        @Min(0) Integer monthlyIncome,
        @Min(0) Integer totalAssets,
        @Min(0) Integer carValue,
        @NotNull Boolean homeless,
        StudentStatus studentStatus
) {}
