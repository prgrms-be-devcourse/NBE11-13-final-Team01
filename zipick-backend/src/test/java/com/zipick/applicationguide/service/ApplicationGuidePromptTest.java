package com.zipick.applicationguide.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.applicationguide.document.ReducedDocument;
import com.zipick.applicationguide.document.SectionCategory;
import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideSourceRef;
import com.zipick.applicationguide.dto.GuideUserContext;
import com.zipick.housing.domain.RentalType;
import com.zipick.user.domain.StudentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 외부 AI 로 무엇이 나가는지 고정하는 테스트.
 *
 * <b>여기서 가장 중요한 것은 "무엇이 나가지 않는가" 다.</b>
 * 소득·자산·자동차 가액은 민감 금융정보라 외부로 내보내지 않기로 했는데,
 * 나중에 누가 프롬프트에 한 줄 보태면서 조용히 들어갈 수 있다. 그걸 막는 울타리다.
 */
class ApplicationGuidePromptTest {

    private static GuideRequestContext context() {
        return new GuideRequestContext(
                new ApplicationGuideResponse.NoticeHeader(93L,
                        "[청년형] 특화형 매입임대주택 입주자 모집 공고", "서울주택도시개발공사", "https://i-sh.co.kr/93"),
                "SH", "매입임대", RentalType.YOUTH_PURCHASE, List.of(),
                new GuideUserContext(24, false, 1, true, StudentStatus.UNIVERSITY),
                LocalDateTime.now(), null);
    }

    private static ReducedDocument document() {
        return new ReducedDocument(
                "[SOURCE_ID: S1]\nFILE: 모집공고.pdf\nPAGE: 4\n제출서류는 다음과 같습니다.",
                Map.of("S1", GuideSourceRef.ofPage("모집공고.pdf", 4),
                        "S2", GuideSourceRef.ofPage("모집공고.pdf", 6)),
                Set.of(SectionCategory.DOCUMENTS), 24_000, 1_200, List.of(4, 6));
    }

    @Test
    void 사용자_정보는_다섯_가지만_보낸다() {
        String message = ApplicationGuidePrompt.userMessage(context(), document());

        assertThat(message)
                .contains("만 24세")
                .contains("미혼")
                .contains("가구원 수: 1명")
                .contains("무주택")
                .contains("대학생");
    }

    @Test
    void 소득_자산_자동차가액은_보내지_않는다() {
        String message = ApplicationGuidePrompt.userMessage(context(), document());

        // 금액 판정은 서버(EligibilityChecker)가 한다. 외부 AI 에게 숫자를 줄 이유가 없다
        assertThat(message).doesNotContain("monthlyIncome", "totalAssets", "carValue");
        assertThat(message).contains("소득·자산·자동차 가액: 제공되지 않음");
        // 금액으로 읽힐 만한 숫자가 사용자 블록에 끼어들지 않았는지
        assertThat(message).doesNotContain("만 원");
    }

    @Test
    void 이메일이나_사용자_번호는_보내지_않는다() {
        String message = ApplicationGuidePrompt.userMessage(context(), document());

        assertThat(message).doesNotContain("@").doesNotContain("userId");
    }

    @Test
    void 공고_정보와_공고문_발췌를_함께_보낸다() {
        String message = ApplicationGuidePrompt.userMessage(context(), document());

        assertThat(message)
                .contains("[청년형] 특화형 매입임대주택 입주자 모집 공고")
                .contains("서울주택도시개발공사")
                .contains(RentalType.YOUTH_PURCHASE.getLabel())
                .contains("[SOURCE_ID: S1]")
                .contains("PAGE: 4");
    }

    @Test
    void 지시문은_최종_자격_단정을_금지한다() {
        assertThat(ApplicationGuidePrompt.SYSTEM_INSTRUCTION)
                .contains("신청 가능 여부를 단정하지 마세요")
                .contains("CHECK_REQUIRED")
                .contains("SOURCE_ID");
    }

    @Test
    void 응답_스키마는_제공한_출처_ID_만_허용한다() {
        // 모델이 S99 같은 없는 출처를 만들어 내지 못하게 스키마에 열거해 둔다
        Map<String, Object> schema = ApplicationGuidePrompt.responseSchema(List.of("S1", "S2"));

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema).containsKey("required");
        assertThat(schema.toString()).contains("S1").contains("S2");
    }

    @Test
    void 응답_스키마는_AI_가_FAIL_상태를_만들_수_없게_한다() {
        String schema = ApplicationGuidePrompt.responseSchema(List.of("S1")).toString();

        assertThat(schema).contains("MATCHED").contains("CHECK_REQUIRED").contains("NOT_APPLICABLE");
        assertThat(schema).doesNotContain("FAIL");
        assertThat(schema).contains("COMMON").contains("PERSONALIZED").contains("CONDITIONAL");
        assertThat(schema).contains("NOTICE").contains("MANUAL");
    }
}
