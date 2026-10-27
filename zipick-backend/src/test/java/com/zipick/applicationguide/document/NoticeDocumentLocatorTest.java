package com.zipick.applicationguide.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.dto.GuideAttachment;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideUserContext;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.user.domain.StudentStatus;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 공고문 찾기.
 *
 * 여기서 가장 중요한 두 가지는
 *   1. 주택목록·도면 같은 엉뚱한 첨부를 분석해 무료 사용량을 쓰지 않는 것
 *   2. 첨부가 DB 에 없는 LH 공고도 상세 페이지를 통해 공고문을 찾아내는 것
 * 이다.
 */
@ExtendWith(MockitoExtension.class)
class NoticeDocumentLocatorTest {

    @Mock ExternalDocumentClient documentClient;

    private NoticeDocumentLocator locator(int maxDocuments) {
        return new NoticeDocumentLocator(documentClient,
                new ApplicationGuideProperties(null, null, null, 0, maxDocuments, 0, 0, null),
                List.of(new LhDocumentFallback(), new GbdcDocumentFallback()));
    }

    private static GuideAttachment attachment(String type, String fileName) {
        return new GuideAttachment(1L, type, fileName, "https://example.test/" + fileName);
    }

    private static GuideRequestContext context(String source, String detailUrl, List<GuideAttachment> attachments) {
        return new GuideRequestContext(
                new ApplicationGuideResponse.NoticeHeader(10L, "공고", "LH", detailUrl),
                source, null, null, attachments,
                new GuideUserContext(24, false, 1, true, StudentStatus.NONE),
                LocalDateTime.now(), null);
    }

    // ---------- DB 첨부에서 고르기 ----------

    @Test
    void 첨부_유형이_ANNOUNCEMENT_면_가장_먼저_고른다() {
        List<LocatedDocument> found = locator(1).select(List.of(
                attachment("OTHER", "신청안내.pdf"),
                attachment("ANNOUNCEMENT", "2026년_입주자모집공고.pdf")));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).isEqualTo("2026년_입주자모집공고.pdf");
        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void 주택목록과_도면은_제외한다() {
        List<LocatedDocument> found = locator(5).select(List.of(
                attachment("HOUSING_LIST", "공급주택목록.xlsx"),
                attachment("FLOOR_PLAN", "평면도.pdf"),
                attachment("OTHER", "단지별 주택목록 공고.pdf")));

        assertThat(found).isEmpty();
    }

    @Test
    void 공고문_단서가_없는_첨부는_쓰지_않는다() {
        // 엉뚱한 첨부를 분석하느니 "찾지 못했다" 고 알리는 편이 낫다
        List<LocatedDocument> found = locator(5).select(List.of(
                attachment("OTHER", "개인정보처리방침.pdf"),
                attachment("OTHER", "붙임1.hwp")));

        assertThat(found).isEmpty();
    }

    @Test
    void 엑셀과_이미지는_형식부터_제외된다() {
        List<LocatedDocument> found = locator(5).select(List.of(
                attachment("ANNOUNCEMENT", "모집공고.xlsx"),
                attachment("ANNOUNCEMENT", "모집공고.jpg")));

        assertThat(found).isEmpty();
    }

    @Test
    void 같은_공고문이면_HWP_보다_PDF_를_고른다() {
        List<LocatedDocument> found = locator(1).select(List.of(
                attachment("OTHER", "모집공고.hwp"),
                attachment("OTHER", "모집공고.pdf")));

        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void HWP_만_있으면_HWP_를_고른다() {
        List<LocatedDocument> found = locator(1).select(List.of(attachment("OTHER", "입주자모집공고.hwp")));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.HWP);
    }

    /**
     * HWPX 는 읽지 못해 결국 UNSUPPORTED_DOCUMENT_FORMAT 이 된다.
     * 읽을 수 있는 HWP 가 같은 공고에 있으면 그쪽이 뽑혀야 한다 (분석은 1건만 한다).
     */
    @Test
    void 같은_공고문이면_HWPX_보다_HWP_를_고른다() {
        List<LocatedDocument> found = locator(1).select(List.of(
                attachment("OTHER", "입주자모집공고.hwpx"),
                attachment("OTHER", "입주자모집공고.hwp")));

        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.HWP);
    }

    @Test
    void HWPX_만_있으면_후보로는_남긴다() {
        // 여기서 빼 버리면 파일이 있는데도 NO_DOCUMENT("찾지 못했다")가 되어 안내가 틀어진다.
        // 형식 거부는 Extractor 가 UNSUPPORTED_DOCUMENT_FORMAT 으로 처리한다.
        List<LocatedDocument> found = locator(1).select(List.of(attachment("ANNOUNCEMENT", "모집공고문.hwpx")));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.HWPX);
    }

    /**
     * LH 는 "20260922철원갈말영구임대예비입주자모집.pdf" 처럼 끝을 '모집' 으로 맺는 작명이 흔하다.
     * '모집공고' 만 보던 때는 이런 파일이 점수 0 으로 제외돼 NO_DOCUMENT 가 됐다 (notice 34).
     */
    @Test
    void 공고라는_말이_없어도_입주자모집이면_공고문으로_본다() {
        List<LocatedDocument> found = locator(1).select(List.of(
                attachment("OTHER", "20260922철원갈말영구임대예비입주자모집.pdf")));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).contains("예비입주자모집");
    }

    @Test
    void 입주자모집을_받아도_주택목록과_도면은_그대로_제외된다() {
        List<LocatedDocument> found = locator(5).select(List.of(
                attachment("OTHER", "26년 입주자모집 공급주택목록.pdf"),
                attachment("OTHER", "입주자모집 평면도.pdf"),
                attachment("OTHER", "최종심사 대상자 명단_게시용.pdf")));

        assertThat(found).isEmpty();
    }


    /**
     * LH 는 파일명에 '공고' 가 없고 '신청'·'서식'·'양식' 이 있으면 APPLICATION_FORM 을 붙인다.
     * PREFERRED_NAMES 에 "신청안내" 가 있어, 유형을 거르지 않으면 서식 파일이 공고문으로 뽑혔다.
     */
    @Test
    void 신청서_서식은_이름이_신청안내여도_공고문으로_쓰지_않는다() {
        List<LocatedDocument> found = locator(5).select(List.of(
                attachment("APPLICATION_FORM", "신청안내 및 서식.hwp")));

        assertThat(found).isEmpty();
    }

    @Test
    void 수집기가_붙이는_도면_사진_위치도_유형은_모두_제외한다() {
        List<LocatedDocument> found = locator(10).select(List.of(
                attachment("FLOOR_PLAN_OR_PHOTO", "붙임 공고 자료1.pdf"),
                attachment("FLOOR_PLAN_OR_PHOTO_INDEX", "붙임 공고 자료2.pdf"),
                attachment("EXTERNAL_FLOOR_PLAN_OR_PHOTO", "붙임 공고 자료3.pdf"),
                attachment("LOCATION_MAP", "붙임 공고 자료4.pdf"),
                attachment("HOUSING_IMAGE", "붙임 공고 자료5.pdf"),
                attachment("SITE_PLAN", "붙임 공고 자료6.pdf"),
                attachment("BUILDING_PHOTO", "붙임 공고 자료7.pdf")));

        assertThat(found).isEmpty();
    }

    @Test
    void 공고문이_함께_있으면_부속자료를_제치고_공고문을_고른다() {
        List<LocatedDocument> found = locator(1).select(List.of(
                attachment("APPLICATION_FORM", "신청안내.hwp"),
                attachment("FLOOR_PLAN_OR_PHOTO", "공고 붙임.pdf"),
                attachment("ANNOUNCEMENT", "입주자모집공고.pdf")));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).isEqualTo("입주자모집공고.pdf");
    }

    @Test
    void maxDocuments_만큼만_고른다() {
        List<LocatedDocument> found = locator(1).select(List.of(
                attachment("ANNOUNCEMENT", "모집공고.pdf"),
                attachment("ANNOUNCEMENT", "모집공고_정정.pdf")));

        assertThat(found).hasSize(1);
    }

    // ---------- LH 폴백 ----------

    @Test
    void SH_공고는_첨부에서_찾으므로_상세페이지를_다시_읽지_않는다() {
        var context = context("SH", "https://www.i-sh.co.kr/notice/10",
                List.of(attachment("ANNOUNCEMENT", "모집공고.pdf")));

        List<LocatedDocument> found = locator(1).locate(context);

        assertThat(found).hasSize(1);
        verifyNoInteractions(documentClient);
    }

    @Test
    void LH_공고는_첨부에_공고문이_없으면_상세페이지에서_찾는다() {
        // LH 수집기는 도면만 notice_attachment 에 저장한다. 공고문은 DB 에 없다.
        String html = """
                <ul>
                  <li><a href="#" onclick="fileDownLoad('111');">공급주택 상세내역.xlsx</a></li>
                  <li><a href="#" onclick="fileDownLoad('222');">2026년 청년매입임대 입주자모집공고.pdf</a></li>
                  <li><a href="#" onclick="fileDownLoad('333');">평면도.pdf</a></li>
                </ul>
                """;
        when(documentClient.getText(URI.create("https://apply.lh.or.kr/detail/10"))).thenReturn(html);
        var context = context("LH", "https://apply.lh.or.kr/detail/10",
                List.of(attachment("FLOOR_PLAN", "평면도.pdf")));

        List<LocatedDocument> found = locator(1).locate(context);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).isEqualTo("2026년 청년매입임대 입주자모집공고.pdf");
        assertThat(found.getFirst().sourceUrl()).isEqualTo("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=222");
    }

    @Test
    void LH_상세페이지_조회가_실패해도_예외를_던지지_않는다() {
        when(documentClient.getText(URI.create("https://apply.lh.or.kr/detail/10")))
                .thenThrow(new IllegalStateException("외부 문서 요청 실패: 500"));

        List<LocatedDocument> found = locator(1).locate(context("LH", "https://apply.lh.or.kr/detail/10", List.of()));

        assertThat(found).isEmpty();
    }

    @Test
    void LH_인데_상세주소가_없으면_조용히_포기한다() {
        List<LocatedDocument> found = locator(1).locate(context("LH", null, List.of()));

        assertThat(found).isEmpty();
        verifyNoInteractions(documentClient);
    }

    @Test
    void 상세페이지_첨부명의_HTML_엔티티를_되돌린다() {
        String html = "<a onclick=\"fileDownLoad('9');\">2026 모집공고(청년&amp;신혼).pdf</a>";
        List<NoticeDocumentLocator.Candidate> candidates =
                new LhDocumentFallback().findCandidates(URI.create("https://apply.lh.or.kr/d"), html);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().fileName()).isEqualTo("2026 모집공고(청년&신혼).pdf");
    }
}
