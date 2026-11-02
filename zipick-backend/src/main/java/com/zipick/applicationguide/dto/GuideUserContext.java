package com.zipick.applicationguide.dto;

import com.zipick.user.domain.StudentStatus;
import com.zipick.user.domain.UserProfile;
import java.time.LocalDate;

/**
 * Gemini 에 보내는 사용자 정보. <b>이 5개가 전부다.</b>
 *
 * 소득·자산·자동차가액(만 원 단위 실제 금액)은 일부러 넣지 않는다.
 * - 민감 금융정보라 외부 AI API 로 내보낼 이유가 없다 (UserProfile 에도 암호화 TODO 가 달려 있다).
 * - 금액 기준 판정은 이미 서버의 EligibilityChecker 가 설정값으로 정확히 계산한다.
 *   AI 에게 산술 비교를 시키면 서로 다른 판정 결과가 두 개 생긴다.
 * - 금액을 몰라도 AI 는 해당 서류를 CONDITIONAL("순위에 따라 필요")로 분류한다.
 *   그게 우리가 원하는 동작이다.
 *
 * 이메일·실명·userId·토큰도 당연히 보내지 않는다. 개인화에 쓸모가 없다.
 *
 * 이 5개를 보내는 이유는 "이 사람이 어떤 신청자 유형인가"를 정하기 위해서다.
 * 신청자 유형이 정해져야 공고문의 어느 서류 목록이 내 것인지 고를 수 있다.
 */
public record GuideUserContext(
        int age,
        boolean married,
        int householdSize,
        boolean homeless,
        StudentStatus studentStatus
) {
    public static GuideUserContext from(UserProfile profile, LocalDate today) {
        return new GuideUserContext(
                profile.age(today),
                profile.isMarried(),
                profile.getHouseholdSize(),
                profile.isHomeless(),
                profile.studentStatusOrNone()
        );
    }
}
