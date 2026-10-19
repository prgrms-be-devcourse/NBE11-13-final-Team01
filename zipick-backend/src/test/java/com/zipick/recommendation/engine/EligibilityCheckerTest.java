package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.user.domain.StudentStatus;
import com.zipick.user.domain.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기준값은 근거 표(2026년 적용, 마이홈·LH)를 그대로 쓴다 */
class EligibilityCheckerTest {

    private final EligibilityChecker checker = new EligibilityChecker(TestFixtures.props());

    private EligibilityResult check(UserProfile p, RentalType type) {
        UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, TestFixtures.props().baseWeights());
        return checker.check(ctx, TestFixtures.house(type, "마포구", HousingType.APARTMENT, "29", 1500, 30));
    }

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
        EligibilityResult r = check(TestFixtures.profile(27, 1, null, 2500), RentalType.HAPPY);
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.CHECK);
        assertThat(r.reasons()).anyMatch(x -> x.mark() == Mark.CHECK && x.text().contains("소득"));
    }

    @Test
    @DisplayName("연령 초과면 FAIL로 제외")
    void failWhenTooOld() {
        EligibilityResult r = check(TestFixtures.profile(45, 1, 260, 2500), RentalType.HAPPY);
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.FAIL);
        assertThat(r.excluded()).isTrue();
    }

    @Test
    @DisplayName("행복주택 청년 1인: 도시근로자 1인 457.6만 원 × 120% = 549만 원까지 통과")
    void happyIncomeBoundary() {
        assertThat(check(TestFixtures.profile(27, 1, 549, 2500), RentalType.HAPPY).status())
                .isEqualTo(EligibilityResult.Status.PASS);
        assertThat(check(TestFixtures.profile(27, 1, 550, 2500), RentalType.HAPPY).status())
                .isEqualTo(EligibilityResult.Status.FAIL);
    }

    @Test
    @DisplayName("행복주택 청년 자산 상한 2억 5,100만 원")
    void happyAssets() {
        assertThat(check(TestFixtures.profile(27, 1, 300, 25100), RentalType.HAPPY).status())
                .isEqualTo(EligibilityResult.Status.PASS);
        assertThat(check(TestFixtures.profile(27, 1, 300, 25101), RentalType.HAPPY).status())
                .isEqualTo(EligibilityResult.Status.FAIL);
    }

    @Test
    @DisplayName("국민임대 소득은 가구원 수별: 1인 90%(411만), 3인 70%(571만)")
    void nationalIncomeByHousehold() {
        assertThat(check(TestFixtures.profile(45, 1, 411, 5000), RentalType.NATIONAL).status())
                .isEqualTo(EligibilityResult.Status.PASS);
        assertThat(check(TestFixtures.profile(45, 1, 412, 5000), RentalType.NATIONAL).status())
                .isEqualTo(EligibilityResult.Status.FAIL);
        assertThat(check(TestFixtures.profile(45, 3, 571, 5000), RentalType.NATIONAL).status())
                .isEqualTo(EligibilityResult.Status.PASS);
    }

    @Test
    @DisplayName("청년 매입임대: 3순위 기준을 넘어도 2순위(부모 합산) 가능성이 있으면 CHECK")
    void purchaseFallbackToSecondRank() {
        EligibilityResult overIncome = check(TestFixtures.profile(27, 1, 500, 2500), RentalType.YOUTH_PURCHASE);
        assertThat(overIncome.status()).isEqualTo(EligibilityResult.Status.CHECK);
        assertThat(overIncome.reasons()).anyMatch(r -> r.text().contains("2순위"));

        // 자산이 2순위 상한(3억 4,500만)도 넘으면 탈락
        assertThat(check(TestFixtures.profile(27, 1, 300, 40000), RentalType.YOUTH_PURCHASE).status())
                .isEqualTo(EligibilityResult.Status.FAIL);
    }

    @Test
    @DisplayName("규칙이 없는 유형(영구임대)과 유형을 모르는 공고는 통과도 탈락도 아닌 CHECK")
    void unknownTypesAreCheck() {
        EligibilityResult permanent = check(TestFixtures.youngSingle(), RentalType.PERMANENT);
        assertThat(permanent.status()).isEqualTo(EligibilityResult.Status.CHECK);
        assertThat(permanent.reasons()).anyMatch(r -> r.text().contains("수급자"));

        assertThat(check(TestFixtures.youngSingle(), null).status()).isEqualTo(EligibilityResult.Status.CHECK);
    }

    @Test
    @DisplayName("일반 매입임대(청년 전용이 아님)는 청년 연령 기준으로 탈락시키지 않고 CHECK")
    void generalPurchaseIsCheck() {
        assertThat(check(TestFixtures.profile(45, 1, 260, 2500), RentalType.PURCHASE).status())
                .isEqualTo(EligibilityResult.Status.CHECK);
    }

    @Test
    @DisplayName("공고 유형이 비면 제목으로 분류: '기존주택매입' → 매입임대, '청년 매입' → 청년 매입임대")
    void classifyByTitle() {
        assertThat(RentalType.classify(null, "2026년 하반기 대구경북지역 기존주택매입 입주자 모집공고")).isEqualTo(RentalType.PURCHASE);
        assertThat(RentalType.classify("", "2026년 제3차 청년 매입임대주택 입주자 모집")).isEqualTo(RentalType.YOUTH_PURCHASE);
        assertThat(RentalType.classify("청년·신혼부부 매입임대리츠", null)).isEqualTo(RentalType.PURCHASE);
        assertThat(RentalType.classify("통합공공임대", null)).isEqualTo(RentalType.INTEGRATED);
        assertThat(RentalType.classify("공공임대", null)).isEqualTo(RentalType.PUBLIC);
        assertThat(RentalType.classify(null, "장애인 자립특화형 주택 입주자 모집공고")).isNull();
    }

    @Test
    @DisplayName("국민임대는 연령 제한이 없다 (45세도 통과)")
    void nationalHasNoAgeLimit() {
        assertThat(check(TestFixtures.profile(45, 1, 260, 2500), RentalType.NATIONAL).status())
                .isEqualTo(EligibilityResult.Status.PASS);
    }

    @Test
    @DisplayName("무주택이 아니면 FAIL")
    void failWhenNotHomeless() {
        UserProfile p = UserProfile.builder().userId(1L).birthDate(java.time.LocalDate.now().minusYears(27))
                .married(false).householdSize(1).monthlyIncome(260).totalAssets(2500).carValue(0).homeless(false)
                .build();
        assertThat(check(p, RentalType.HAPPY).status()).isEqualTo(EligibilityResult.Status.FAIL);
    }

    private UserProfile married(int age, StudentStatus student) {
        return UserProfile.builder().userId(1L).birthDate(java.time.LocalDate.now().minusYears(age))
                .married(true).householdSize(2).monthlyIncome(300).totalAssets(5000).carValue(0).homeless(true)
                .studentStatus(student).build();
    }

    @Test
    @DisplayName("청년 매입임대는 미혼만 대상: 기혼이면 FAIL")
    void youthPurchaseRequiresSingle() {
        assertThat(check(married(30, StudentStatus.NONE), RentalType.YOUTH_PURCHASE).status())
                .isEqualTo(EligibilityResult.Status.FAIL);
    }

    @Test
    @DisplayName("행복주택은 기혼이면 청년 규칙 대신 신혼부부 계층 가능성으로 CHECK (연령으로 탈락시키지 않음)")
    void happyMarriedIsCheckNotFail() {
        EligibilityResult r = check(married(42, StudentStatus.NONE), RentalType.HAPPY);
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.CHECK);
        assertThat(r.reasons()).anyMatch(x -> x.text().contains("신혼부부"));
        assertThat(r.reasons()).noneMatch(x -> x.mark() == Mark.FAIL);
    }

    @Test
    @DisplayName("청년 매입임대: 연령 밖이라도 대학생·취업준비생이면 대상")
    void studentExtendsAge() {
        UserProfile student = UserProfile.builder().userId(1L).birthDate(java.time.LocalDate.now().minusYears(18))
                .married(false).householdSize(1).monthlyIncome(0).totalAssets(500).carValue(0).homeless(true)
                .studentStatus(StudentStatus.UNIVERSITY).build();
        EligibilityResult r = check(student, RentalType.YOUTH_PURCHASE);
        assertThat(r.status()).isEqualTo(EligibilityResult.Status.PASS);
        assertThat(r.reasons()).anyMatch(x -> x.text().contains("대학생"));

        UserProfile notStudent = UserProfile.builder().userId(1L).birthDate(java.time.LocalDate.now().minusYears(18))
                .married(false).householdSize(1).monthlyIncome(0).totalAssets(500).carValue(0).homeless(true).build();
        assertThat(check(notStudent, RentalType.YOUTH_PURCHASE).status()).isEqualTo(EligibilityResult.Status.FAIL);
    }
}
