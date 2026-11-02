package com.zipick.recommendation.engine;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.RentalType;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.config.RecommendationProperties.EligibilityRule;
import com.zipick.user.domain.StudentStatus;
import com.zipick.user.domain.UserProfile;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 1단계: 공고 지원조건 판정.
 *
 * 조건마다 PASS / CHECK(판단 불가) / FAIL 로 나누고, FAIL이 하나라도 있으면 추천에서 제외한다.
 * - 사용자가 정보를 안 적었으면 CHECK (모르는 걸 탈락시키지 않는다)
 * - 공공임대 유형을 모르거나 규칙이 없는 유형이면 CHECK (모르는 걸 통과시키지도 않는다)
 * - 기준을 넘었지만 다른 순위로 가능할 수 있으면(fallback) CHECK
 *
 * 기준값은 설정 파일(zipick.recommendation.eligibility)에 있고, 출처는 문서의 근거 표에 있다.
 */
@Component
@RequiredArgsConstructor
public class EligibilityChecker {

    private static final String ITEM = "ELIGIBILITY";

    private final RecommendationProperties props;

    public EligibilityResult check(UserContext ctx, Candidate c) {
        RentalType type = c.rentalType();
        List<Reason> reasons = new ArrayList<>();
        if (type == null) {
            reasons.add(Reason.check(ITEM, "공공임대 유형을 알 수 없어 지원조건은 공고문 확인 필요"));
            return new EligibilityResult(EligibilityResult.Status.CHECK, reasons);
        }
        EligibilityRule rule = props.eligibility() == null ? null : props.eligibility().get(type);
        if (rule == null || isEmpty(rule)) {
            String note = rule != null && rule.note() != null ? rule.note() : type.getLabel() + " 지원조건은 공고문 확인 필요";
            reasons.add(Reason.check(ITEM, note));
            return new EligibilityResult(EligibilityResult.Status.CHECK, reasons);
        }

        UserProfile p = ctx.profile();
        boolean fail = false;
        boolean check = false;
        boolean overIncomeOrAssets = false;

        // 혼인: 미혼 전용 계층(청년)이면 기혼은 탈락. 다른 계층(신혼부부)일 수 있으면 '확인 필요'로 두고 연령은 보지 않는다
        boolean skipAge = false;
        if (Boolean.TRUE.equals(rule.requiresSingle()) && p.isMarried()) {
            if (rule.marriedNote() != null) {
                reasons.add(Reason.check(ITEM, rule.marriedNote()));
                check = true;
                skipAge = true;
            } else {
                reasons.add(Reason.fail(ITEM, "미혼 청년 대상 (혼인 중인 경우 신청 불가)"));
                fail = true;
            }
        }

        // 연령 (대학생·취업준비생은 연령 밖이어도 대상인 유형이 있다)
        if (!skipAge && (rule.minAge() != null || rule.maxAge() != null)) {
            int age = p.age(ctx.today());
            boolean inRange = (rule.minAge() == null || age >= rule.minAge())
                    && (rule.maxAge() == null || age <= rule.maxAge());
            boolean student = p.studentStatusOrNone() != StudentStatus.NONE;
            if (inRange) {
                reasons.add(Reason.pass(ITEM, "대상 연령 조건 충족 (만 " + age + "세)"));
            } else if (Boolean.TRUE.equals(rule.studentExtendsAge()) && student) {
                reasons.add(Reason.pass(ITEM, (p.studentStatusOrNone() == StudentStatus.UNIVERSITY ? "대학생" : "취업준비생")
                        + " 자격으로 대상 (만 " + age + "세)"));
            } else {
                reasons.add(Reason.fail(ITEM, "대상 연령 아님 (만 " + age + "세, 기준 "
                        + rangeText(rule.minAge(), rule.maxAge()) + "세)"));
                fail = true;
            }
        }

        // 무주택
        if (Boolean.TRUE.equals(rule.requiresHomeless())) {
            if (p.isHomeless()) reasons.add(Reason.pass(ITEM, "무주택 조건 충족"));
            else { reasons.add(Reason.fail(ITEM, "무주택 세대구성원이 아님")); fail = true; }
        }

        // 소득 (도시근로자 월평균소득 × 비율, 가구원 수별)
        Integer incomeLimit = props.incomeLimitManwon(rule, p.getHouseholdSize());
        if (incomeLimit != null) {
            if (p.getMonthlyIncome() == null) {
                reasons.add(Reason.check(ITEM, "소득 정보 추가 확인 필요")); check = true;
            } else if (p.getMonthlyIncome() <= incomeLimit) {
                reasons.add(Reason.pass(ITEM, "소득 기준 이내 (월 " + p.getMonthlyIncome() + "만 원 / 기준 " + incomeLimit + "만 원)"));
            } else {
                reasons.add(Reason.fail(ITEM, "소득 기준 초과 (월 " + p.getMonthlyIncome() + "만 원 / 기준 " + incomeLimit + "만 원)"));
                overIncomeOrAssets = true;
            }
        }

        // 총자산
        if (rule.maxTotalAssets() != null) {
            if (p.getTotalAssets() == null) {
                reasons.add(Reason.check(ITEM, "자산 정보 추가 확인 필요")); check = true;
            } else if (p.getTotalAssets() <= rule.maxTotalAssets()) {
                reasons.add(Reason.pass(ITEM, "총자산 기준 이내"));
            } else {
                reasons.add(Reason.fail(ITEM, "총자산 기준 초과 (" + p.getTotalAssets() + "만 원 / 기준 "
                        + rule.maxTotalAssets() + "만 원)"));
                overIncomeOrAssets = true;
            }
        }

        // 자동차가액
        if (rule.maxCarValue() != null) {
            if (p.getCarValue() == null) {
                reasons.add(Reason.check(ITEM, "자동차가액 추가 확인 필요")); check = true;
            } else if (p.getCarValue() <= rule.maxCarValue()) {
                reasons.add(Reason.pass(ITEM, "자동차가액 기준 이내"));
            } else {
                reasons.add(Reason.fail(ITEM, "자동차가액 기준 초과"));
                fail = true;
            }
        }

        // 소득·자산을 넘었지만 다른 순위(예: 청년 매입임대 2순위)로 가능할 수 있으면 탈락 대신 확인 필요
        if (overIncomeOrAssets) {
            var fb = rule.fallback();
            boolean withinFallback = fb != null
                    && (fb.maxTotalAssets() == null || p.getTotalAssets() == null || p.getTotalAssets() <= fb.maxTotalAssets());
            if (withinFallback) {
                reasons.add(Reason.check(ITEM, fb.note()));
                check = true;
            } else {
                fail = true;
            }
        }

        EligibilityResult.Status status = fail ? EligibilityResult.Status.FAIL
                : check ? EligibilityResult.Status.CHECK : EligibilityResult.Status.PASS;
        return new EligibilityResult(status, reasons);
    }

    private static boolean isEmpty(EligibilityRule r) {
        return r.minAge() == null && r.maxAge() == null && r.requiresHomeless() == null && r.incomePercent() == null
                && r.maxTotalAssets() == null && r.maxCarValue() == null && r.requiresSingle() == null;
    }

    private String rangeText(Integer min, Integer max) {
        if (min != null && max != null) return min + "~" + max;
        if (min != null) return min + " 이상";
        return max + " 이하";
    }
}
