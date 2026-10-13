package com.zipick.user.dto;

import com.zipick.user.domain.UserProfile;
import java.time.LocalDate;

public record UserProfileResponse(
        LocalDate birthDate,
        String job,
        boolean married,
        int householdSize,
        Integer monthlyIncome,
        Integer totalAssets,
        Integer carValue,
        boolean homeless,
        Boolean student,
        Integer subscriptionMonths,
        Integer subscriptionPayments
) {
    public static UserProfileResponse from(UserProfile p) {
        return new UserProfileResponse(p.getBirthDate(), p.getJob(), p.isMarried(), p.getHouseholdSize(),
                p.getMonthlyIncome(), p.getTotalAssets(), p.getCarValue(), p.isHomeless(),
                p.getStudent(), p.getSubscriptionMonths(), p.getSubscriptionPayments());
    }
}
