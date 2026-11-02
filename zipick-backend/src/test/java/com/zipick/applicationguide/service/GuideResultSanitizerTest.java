package com.zipick.applicationguide.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.applicationguide.dto.GuideResult;
import com.zipick.applicationguide.dto.GuideResult.CautionKind;
import com.zipick.applicationguide.dto.GuideResult.CheckStatus;
import com.zipick.applicationguide.dto.GuideResult.DocumentCategory;
import com.zipick.applicationguide.dto.GuideSourceRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AI 응답 다듬기.
 *
 * 스키마로 형태는 강제할 수 있어도 내용까지 보장되지는 않는다. 특히 출처가 그렇다.
 * 없는 출처가 화면에 "모집공고.pdf 99쪽" 으로 뜨면 사용자가 원문에서 찾지 못하고,
 * 그 순간 가이드 전체를 못 믿게 된다.
 */
class GuideResultSanitizerTest {

    private static final Map<String, GuideSourceRef> KNOWN = Map.of(
            "S1", GuideSourceRef.ofPage("모집공고.pdf", 4),
            "S2", GuideSourceRef.ofPage("모집공고.pdf", 6));

    private static GuideResult result(GuideResult.DocumentItem... documents) {
        return new GuideResult("요약", List.of(), List.of(documents), List.of());
    }

    private static GuideResult.DocumentItem document(String name, String requirements, String... sourceIds) {
        return new GuideResult.DocumentItem(name, DocumentCategory.COMMON, "이유", requirements, List.of(sourceIds));
    }

    @Test
    void 제공하지_않은_출처는_지운다() {
        GuideResult sanitized = GuideResultSanitizer.sanitize(
                result(document("주민등록등본", "", "S1", "S99")), KNOWN);

        assertThat(sanitized.documents().getFirst().sourceIds()).containsExactly("S1");
    }

    @Test
    void 출처가_모두_엉터리여도_항목_자체는_남긴다() {
        // 출처가 없어도 "주민등록등본이 필요하다" 는 정보는 여전히 쓸모가 있다.
        // 서류가 통째로 사라지는 쪽이 사용자에게 더 위험하다.
        GuideResult sanitized = GuideResultSanitizer.sanitize(
                result(document("주민등록등본", "", "S99")), KNOWN);

        assertThat(sanitized.documents()).hasSize(1);
        assertThat(sanitized.documents().getFirst().sourceIds()).isEmpty();
    }

    @Test
    void 같은_출처를_여러_번_써도_한_번만_남긴다() {
        GuideResult sanitized = GuideResultSanitizer.sanitize(
                result(document("주민등록등본", "", "S1", "S1", "S2")), KNOWN);

        assertThat(sanitized.documents().getFirst().sourceIds()).containsExactly("S1", "S2");
    }

    @Test
    void 이름이_없는_서류는_버린다() {
        GuideResult sanitized = GuideResultSanitizer.sanitize(
                result(document("주민등록등본", "", "S1"), document("  ", "", "S1")), KNOWN);

        assertThat(sanitized.documents()).hasSize(1);
    }

    @Test
    void 공고문에_없어_비워_둔_준비조건은_null_로_둔다() {
        // 화면에서 빈 줄 하나를 덜 그리게 한다
        GuideResult sanitized = GuideResultSanitizer.sanitize(result(document("주민등록등본", "", "S1")), KNOWN);

        assertThat(sanitized.documents().getFirst().requirements()).isNull();
    }

    @Test
    void 확인사항과_유의사항에도_같은_규칙을_적용한다() {
        GuideResult raw = new GuideResult("요약",
                List.of(new GuideResult.PersonalCheck("대학생 자격", CheckStatus.MATCHED, "설명",
                        List.of("S2", "S77"))),
                List.of(),
                List.of(new GuideResult.Caution(CautionKind.NOTICE, "중복신청은 무효입니다", List.of("S42")),
                        new GuideResult.Caution(CautionKind.MANUAL, "순위를 직접 확인하세요", List.of())));

        GuideResult sanitized = GuideResultSanitizer.sanitize(raw, KNOWN);

        assertThat(sanitized.personalChecks().getFirst().sourceIds()).containsExactly("S2");
        assertThat(sanitized.cautions().get(0).sourceIds()).isEmpty();
        // 직접 확인해야 할 항목은 원래 출처가 없는 것이 정상이다
        assertThat(sanitized.cautions().get(1).kind()).isEqualTo(CautionKind.MANUAL);
        assertThat(sanitized.summary()).isEqualTo("요약");
    }
}
