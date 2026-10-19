package com.zipick.user.dto;

import com.zipick.user.domain.StudentStatus;
import com.zipick.user.domain.UserProfile;
import java.time.LocalDate;

public record UserProfileResponse(
        LocalDate birthDate,
        boolean married,
        int householdSize,
        Integer monthlyIncome,
        Integer totalAssets,
        Integer carValue,
        boolean homeless,
        StudentStatus studentStatus
) {
    public static UserProfileResponse from(UserProfile p) {
        return new UserProfileResponse(p.getBirthDate(), p.isMarried(), p.getHouseholdSize(),
                p.getMonthlyIncome(), p.getTotalAssets(), p.getCarValue(), p.isHomeless(), p.studentStatusOrNone());
    }
}
