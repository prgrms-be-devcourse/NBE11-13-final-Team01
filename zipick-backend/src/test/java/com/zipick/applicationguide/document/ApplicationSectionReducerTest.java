package com.zipick.applicationguide.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 공고문에서 신청 준비 관련 부분만 고르는 규칙.
 *
 * 실제 SH 모집공고문(11쪽)으로 확인했을 때 신청자격·제출서류·신청방법·유의사항이 모두 들어 있는
 * 8~11쪽이 선택되고, 주택 목록만 있는 앞쪽은 제외됐다. 여기서는 그 규칙을 고정해 둔다.
 */
class ApplicationSectionReducerTest {

    private static final String ELIGIBILITY_PAGE = """
            03 입주신청 자격
            신청자격은 모집공고일 현재 서울시에 거주하는 무주택자로서
            소득기준과 자산기준을 충족하여야 합니다.
            신청순위는 1순위부터 3순위까지 있습니다.
            """;
    private static final String DOCUMENTS_PAGE = """
            04 제출서류
            구비서류 목록은 다음과 같습니다.
            주민등록등본, 가족관계증명서, 재학증명서
            증빙서류는 공고일 이후 발급분만 인정합니다.
            """;
    private static final String PROCESS_PAGE = """
            05 신청방법
            접수방법은 인터넷청약을 원칙으로 합니다.
            신청절차는 다음과 같습니다.
            """;
    private static final String CAUTIONS_PAGE = """
            06 유의사항
            중복신청 시 모두 무효 처리됩니다.
            부적격 당첨자는 당첨취소 됩니다.
            """;
    private static final String HOUSING_LIST_PAGE = """
            공급주택 목록
            1 강남구 역삼동 123-4 59.8 2 일반형 신규
            2 송파구 잠실동 567-8 49.2 1 전세형 재공급
            """;

    private ApplicationSectionReducer reducer(int maxInputChars, int neighborPages) {
        return new ApplicationSectionReducer(
                new ApplicationGuideProperties(null, null, null, maxInputChars, 1, neighborPages, 0, null));
    }

    private static ExtractedDocument document(String... pages) {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            blocks.add(new DocumentBlock(i, i + 1, "p" + (i + 1), pages[i]));
        }
        return new ExtractedDocument("모집공고.pdf", DocumentFormat.PDF, blocks);
    }

    @Test
    void 신청_준비_관련_쪽만_고르고_주택목록은_버린다() {
        ExtractedDocument doc = document(
                HOUSING_LIST_PAGE, HOUSING_LIST_PAGE, HOUSING_LIST_PAGE,
                ELIGIBILITY_PAGE, DOCUMENTS_PAGE, PROCESS_PAGE, CAUTIONS_PAGE);

        ReducedDocument reduced = reducer(30_000, 0).reduce(doc).orElseThrow();

        assertThat(reduced.selectedPages()).containsExactly(4, 5, 6, 7);
        assertThat(reduced.categories()).containsExactlyInAnyOrder(
                SectionCategory.ELIGIBILITY, SectionCategory.DOCUMENTS,
                SectionCategory.PROCESS, SectionCategory.CAUTIONS);
        assertThat(reduced.text()).doesNotContain("공급주택 목록");
    }

    @Test
    void 관련_내용이_없으면_전체를_보내지_않고_포기한다() {
        // 여기서 전체 문서를 보내는 폴백을 두면 150쪽짜리를 통째로 보내는 일이 생긴다.
        // 무료 사용량을 한 번에 태우면서 품질도 보장할 수 없어 일부러 만들지 않았다.
        Optional<ReducedDocument> reduced = reducer(30_000, 1)
                .reduce(document(HOUSING_LIST_PAGE, HOUSING_LIST_PAGE));

        assertThat(reduced).isEmpty();
    }

    @Test
    void 관련_쪽의_앞뒤_쪽을_문맥으로_함께_담는다() {
        // 제출서류 표가 쪽을 넘어가는 경우가 많아 앞뒤를 함께 보내야 내용이 잘리지 않는다
        ExtractedDocument doc = document(HOUSING_LIST_PAGE, HOUSING_LIST_PAGE, DOCUMENTS_PAGE, HOUSING_LIST_PAGE);

        ReducedDocument reduced = reducer(30_000, 1).reduce(doc).orElseThrow();

        assertThat(reduced.selectedPages()).containsExactly(2, 3, 4);
    }

    @Test
    void 공백이_벌어진_제목도_잡는다() {
        // 공고문 PDF 는 표 안에서 "신 청 자 격" 처럼 글자 사이가 벌어진 채 추출되는 일이 흔하다
        ExtractedDocument doc = document(HOUSING_LIST_PAGE,
                "3. 신 청 자 격\n무주택 세대구성원이어야 합니다.\n소 득 기 준 을 확인하세요.");

        ReducedDocument reduced = reducer(30_000, 0).reduce(doc).orElseThrow();

        assertThat(reduced.selectedPages()).containsExactly(2);
        assertThat(reduced.categories()).contains(SectionCategory.ELIGIBILITY);
    }

    @Test
    void 글자_예산을_넘기지_않는다() {
        ExtractedDocument doc = document(ELIGIBILITY_PAGE, DOCUMENTS_PAGE, PROCESS_PAGE, CAUTIONS_PAGE);
        int budget = 200;

        ReducedDocument reduced = reducer(budget, 0).reduce(doc).orElseThrow();

        // 예산은 본문 기준이다 (SOURCE_ID/FILE/PAGE 머리말은 서버가 덧붙이는 것이라 따로 센다)
        int bodyChars = reduced.selectedPages().stream()
                .mapToInt(page -> doc.blocks().get(page - 1).length())
                .sum();
        assertThat(bodyChars).isLessThanOrEqualTo(budget);
        assertThat(reduced.selectedPages()).isNotEmpty().hasSizeLessThan(doc.blockCount());
    }

    @Test
    void 예산이_빠듯해도_영역별_대표_쪽을_먼저_담는다() {
        // 네 영역 중 하나가 통째로 빠지면 가이드의 한 꼭지가 비어 버린다
        ExtractedDocument doc = document(ELIGIBILITY_PAGE, DOCUMENTS_PAGE, PROCESS_PAGE, CAUTIONS_PAGE);

        ReducedDocument reduced = reducer(600, 0).reduce(doc).orElseThrow();

        assertThat(reduced.selectedPages()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void 문서_순서대로_SOURCE_ID_를_매긴다() {
        // 순서가 뒤섞이면 AI 가 앞뒤 문맥을 잇지 못한다
        ExtractedDocument doc = document(HOUSING_LIST_PAGE, CAUTIONS_PAGE, HOUSING_LIST_PAGE, ELIGIBILITY_PAGE);

        ReducedDocument reduced = reducer(30_000, 0).reduce(doc).orElseThrow();

        assertThat(reduced.sources().get("S1").page()).isEqualTo(2);
        assertThat(reduced.sources().get("S2").page()).isEqualTo(4);
        assertThat(reduced.text().indexOf("[SOURCE_ID: S1]")).isLessThan(reduced.text().indexOf("[SOURCE_ID: S2]"));
    }

    @Test
    void 출처는_실제_파일명과_쪽수를_가리킨다() {
        // AI 가 쪽 번호를 지어내지 못하게, 우리가 준 S1 만 돌려받아 서버가 변환한다
        ReducedDocument reduced = reducer(30_000, 0)
                .reduce(document(DOCUMENTS_PAGE)).orElseThrow();

        assertThat(reduced.sources()).hasSize(1);
        assertThat(reduced.sources().get("S1").fileName()).isEqualTo("모집공고.pdf");
        assertThat(reduced.sources().get("S1").label()).isEqualTo("모집공고.pdf 1쪽");
        assertThat(reduced.text()).contains("[SOURCE_ID: S1]").contains("PAGE: 1");
    }

    @Test
    void HWP_처럼_쪽이_없으면_출처를_소제목으로_대신한다() {
        ExtractedDocument doc = new ExtractedDocument("모집공고.hwp", DocumentFormat.HWP,
                List.of(new DocumentBlock(0, null, "04 제출서류", DOCUMENTS_PAGE)));

        ReducedDocument reduced = reducer(30_000, 0).reduce(doc).orElseThrow();

        assertThat(reduced.sources().get("S1").page()).isNull();
        assertThat(reduced.sources().get("S1").label()).isEqualTo("모집공고.hwp · 04 제출서류");
        assertThat(reduced.text()).contains("SECTION: 04 제출서류");
    }

    @Test
    void 줄인_비율을_계산한다() {
        ExtractedDocument doc = document(
                HOUSING_LIST_PAGE, HOUSING_LIST_PAGE, HOUSING_LIST_PAGE, HOUSING_LIST_PAGE, DOCUMENTS_PAGE);

        ReducedDocument reduced = reducer(30_000, 0).reduce(doc).orElseThrow();

        assertThat(reduced.originalChars()).isEqualTo(doc.totalChars());
        // 본문 기준으로 센다. 전송 길이로 재면 출처 머리말 때문에 짧은 공고문에서 음수가 나온다
        assertThat(reduced.bodyChars()).isLessThan(reduced.originalChars());
        assertThat(reduced.reductionPercent()).isGreaterThan(0);
        assertThat(reduced.sentChars()).isGreaterThan(reduced.bodyChars());
        assertThat(reduced.sourceHeaderChars()).isPositive();
    }
}
