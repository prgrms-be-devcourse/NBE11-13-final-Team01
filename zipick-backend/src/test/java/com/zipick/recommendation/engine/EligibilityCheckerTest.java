package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.user.domain.UserProfile;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EligibilityCheckerTest {

    private final EligibilityChecker checker = new EligibilityChecker(TestFixtures.props());

    @Test
    @DisplayName("조건을 모두 충족하면 PASS")
    void pass() {
        EligibilityResult r = checker.check(TestFixtures.context(), TestFixtures.goodHouse());
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.PASS);
        assertThat(r.reasons()).allMatch(x -> x.mark() == Mark.PASS);
    }

    @Test
    @DisplayName("소득이 비어 있으면 제외하지 않고 CHECK")
    void checkWhenIncomeMissing() {
        UserProfile p = UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(27))
                .married(false).householdSize(1).monthlyIncome(null).totalAssets(2500).carValue(0).homeless(true)
                .build();
        UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, TestFixtures.props().baseWeights());
        EligibilityResult r = checker.check(ctx, TestFixtures.goodHouse());
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.CHECK);
        assertThat(r.excluded()).isFalse();
        assertThat(r.reasons()).anyMatch(x -> x.mark() == Mark.CHECK && x.text().contains("소득"));
    }

    @Test
    @DisplayName("연령 초과면 FAIL로 제외")
    void failWhenTooOld() {
        UserProfile p = UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(45))
                .married(false).householdSize(1).monthlyIncome(260).totalAssets(2500).carValue(0).homeless(true)
                .build();
        UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, TestFixtures.props().baseWeights());
        EligibilityResult r = checker.check(ctx, TestFixtures.goodHouse());
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.FAIL);
        assertThat(r.excluded()).isTrue();
    }

    @Test
    @DisplayName("행복주택은 혼인 가구면 연령 조건을 적용하지 않는다")
    void marriedBypassesAgeForHappy() {
        UserProfile p = UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(45))
                .married(true).householdSize(2).monthlyIncome(300).totalAssets(5000).carValue(0).homeless(true)
                .build();
        UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, TestFixtures.props().baseWeights());
        var happy = TestFixtures.house(RentalType.HAPPY, "11440", HousingType.APARTMENT, "26", 2400, 21);
        EligibilityResult r = checker.check(ctx, happy);
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.PASS);
    }

    @Test
    @DisplayName("유주택이면 FAIL")
    void failWhenNotHomeless() {
        UserProfile p = UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(27))
                .married(false).householdSize(1).monthlyIncome(260).totalAssets(2500).carValue(0).homeless(false)
                .build();
        UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, TestFixtures.props().baseWeights());
        assertThat(checker.check(ctx, TestFixtures.goodHouse()).excluded()).isTrue();
    }

    @Test
    @DisplayName("국민임대는 연령 제한이 없다 (45세도 통과)")
    void nationalHasNoAgeLimit() {
        UserProfile p = UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(45))
                .married(false).householdSize(1).monthlyIncome(260).totalAssets(2500).carValue(0).homeless(true)
                .build();
        UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, TestFixtures.props().baseWeights());
        EligibilityResult r = checker.check(ctx, TestFixtures.house(RentalType.NATIONAL, "11440",
                HousingType.APARTMENT, "39", 2000, 25));
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.PASS);
    }
}
