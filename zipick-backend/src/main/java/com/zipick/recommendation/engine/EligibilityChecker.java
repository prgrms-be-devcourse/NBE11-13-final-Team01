package com.zipick.recommendation.engine;

import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.config.RecommendationProperties.EligibilityRule;
import com.zipick.user.domain.UserProfile;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 1단계: 공고 지원조건 판정.
 * 조건마다 PASS / CHECK(판단 불가) / FAIL 로 나누고, FAIL이 하나라도 있으면 추천에서 제외한다.
 * 기준값은 설정 파일(zipick.recommendation.eligibility)에 있고 참고용이다.
 */
@Component
@RequiredArgsConstructor
public class EligibilityChecker {

    private static final String ITEM = "ELIGIBILITY";

    private final RecommendationProperties props;

    public EligibilityResult check(UserContext ctx, SupplyHouse house) {
        RentalType type = house.getAnnouncement().getRentalType();
        EligibilityRule rule = props.eligibility() == null ? null : props.eligibility().get(type);
        List<Reason> reasons = new ArrayList<>();
        if (rule == null) {
            reasons.add(Reason.check(ITEM, "지원조건은 공고문에서 확인 필요"));
            return new EligibilityResult(EligibilityResult.Status.CHECK, reasons);
        }

        UserProfile p = ctx.profile();
        boolean fail = false;
        boolean check = false;

        // 연령
        if (rule.minAge() != null || rule.maxAge() != null) {
            int age = p.age(ctx.today());
            boolean bypass = Boolean.TRUE.equals(rule.marriedBypassesAge()) && p.isMarried();
            boolean inRange = (rule.minAge() == null || age >= rule.minAge())
                    && (rule.maxAge() == null || age <= rule.maxAge());
            if (inRange) {
                reasons.add(Reason.pass(ITEM, "대상 연령 조건 충족 (만 " + age + "세)"));
            } else if (bypass) {
                reasons.add(Reason.pass(ITEM, "혼인 가구로 연령 조건 적용 제외"));
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

        // 소득
        if (rule.maxMonthlyIncome() != null) {
            if (p.getMonthlyIncome() == null) {
                reasons.add(Reason.check(ITEM, "소득 정보 추가 확인 필요")); check = true;
            } else if (p.getMonthlyIncome() <= rule.maxMonthlyIncome()) {
                reasons.add(Reason.pass(ITEM, "소득 기준 이내 (월 " + p.getMonthlyIncome() + "만 원)"));
            } else {
                reasons.add(Reason.fail(ITEM, "소득 기준 초과 (월 " + p.getMonthlyIncome() + "만 원, 기준 "
                        + rule.maxMonthlyIncome() + "만 원)"));
                fail = true;
            }
        }

        // 총자산
        if (rule.maxTotalAssets() != null) {
            if (p.getTotalAssets() == null) {
                reasons.add(Reason.check(ITEM, "자산 정보 추가 확인 필요")); check = true;
            } else if (p.getTotalAssets() <= rule.maxTotalAssets()) {
                reasons.add(Reason.pass(ITEM, "총자산 기준 이내"));
            } else {
                reasons.add(Reason.fail(ITEM, "총자산 기준 초과 (" + p.getTotalAssets() + "만 원, 기준 "
                        + rule.maxTotalAssets() + "만 원)"));
                fail = true;
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

        EligibilityResult.Status status = fail ? EligibilityResult.Status.FAIL
                : check ? EligibilityResult.Status.CHECK : EligibilityResult.Status.PASS;
        return new EligibilityResult(status, reasons);
    }

    private String rangeText(Integer min, Integer max) {
        if (min != null && max != null) return min + "~" + max;
        if (min != null) return min + " 이상";
        return max + " 이하";
    }
}
