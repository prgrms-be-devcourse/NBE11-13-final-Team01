package com.zipick.applicationguide.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.dto.GuideAttachment;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideUserContext;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.user.domain.StudentStatus;
import java.time.LocalDateTime;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 경상북도개발공사(GBDC) 상세 페이지 폴백.
 *
 * HTML 은 notice 58(영주시 드림주택 청년 매입임대)의 실제 구조를 그대로 옮긴 것이다.
 * 화면상 버튼은 javascript:void(0) 지만 실제 앵커에는 평범한 GET 주소가 있고,
 * 원본 파일명이 fileOrgName 에 URL 인코딩되어 함께 들어 있다.
 */
@ExtendWith(MockitoExtension.class)
class GbdcDocumentFallbackTest {

    @Mock ExternalDocumentClient documentClient;

    private static final URI DETAIL = URI.create("https://www.gbdc.co.kr/boardview/boardview.do?seqId=0000003638");

    /** notice 58 의 실제 첨부 6건 (파일명은 사이트와 동일한 URL 인코딩 형태) */
    private static final String HTML = """
            <ul class="download-list">
              <li><a href="/common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2fx%2f4a487e1e.hwp&fileOrgName=1.+2026%eb%85%84+%ec%98%81%ec%a3%bc%ec%8b%9c+%eb%93%9c%eb%a6%bc%ec%a3%bc%ed%83%9d+%ec%9e%85%ec%a3%bc%ec%9e%90+%eb%b0%8f+%ec%98%88%eb%b9%84%ec%9e%85%ec%a3%bc%ec%9e%90+%eb%aa%a8%ec%a7%91%ea%b3%b5%ea%b3%a0.hwp">내려받기</a></li>
              <li><a href="/common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2fx%2f782f1272.pdf&fileOrgName=1-1.+2026%eb%85%84+%ec%98%81%ec%a3%bc%ec%8b%9c+%eb%93%9c%eb%a6%bc%ec%a3%bc%ed%83%9d+%ec%9e%85%ec%a3%bc%ec%9e%90+%eb%b0%8f+%ec%98%88%eb%b9%84%ec%9e%85%ec%a3%bc%ec%9e%90+%eb%aa%a8%ec%a7%91%ea%b3%b5%ea%b3%a0.pdf">내려받기</a></li>
              <li><a href="/common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2fx%2f9976511c.xlsx&fileOrgName=2.+%ea%b3%b5%ea%b8%89%eb%8c%80%ec%83%81+%ec%a3%bc%ed%83%9d%eb%aa%a9%eb%a1%9d%2820260922%29.xlsx">내려받기</a></li>
              <li><a href="/common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2fx%2fb54a33b4.pdf&fileOrgName=2-1.+%ea%b3%b5%ea%b8%89%eb%8c%80%ec%83%81+%ec%a3%bc%ed%83%9d%eb%aa%a9%eb%a1%9d%2820260922%29.pdf">내려받기</a></li>
              <li><a href="/common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2fx%2f7ded0308.hwp&fileOrgName=3.+%5b%ec%8b%a0%ec%b2%ad%ec%84%9c%eb%a5%98%5d+%ec%a0%9c%ec%b6%9c%ec%84%9c%eb%a5%98+%ec%96%91%ec%8b%9d.hwp">내려받기</a></li>
              <li><a href="/common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2fx%2f178920a9.pdf&fileOrgName=%5b%eb%b3%84%ec%b2%a8%5d+%ed%8f%89%eb%a9%b4%eb%8f%84%28%ec%b6%9c%ec%9e%85%ea%b5%ac+%ed%99%95%ec%9d%b8%ec%9a%a9%29.pdf">내려받기</a></li>
            </ul>
            """;

    private NoticeDocumentLocator locator() {
        return new NoticeDocumentLocator(documentClient,
                new ApplicationGuideProperties(null, null, null, 0, 1, 0, 0, null),
                List.of(new LhDocumentFallback(), new GbdcDocumentFallback()));
    }

    @Test
    void 첨부_6건을_모두_읽고_파일명을_디코딩한다() {
        List<NoticeDocumentLocator.Candidate> candidates =
                new GbdcDocumentFallback().findCandidates(DETAIL, HTML);

        assertThat(candidates).hasSize(6);
        assertThat(candidates.get(1).fileName())
                .isEqualTo("1-1. 2026년 영주시 드림주택 입주자 및 예비입주자 모집공고.pdf");
    }

    @Test
    void 다운로드_주소를_절대경로로_만든다() {
        List<NoticeDocumentLocator.Candidate> candidates =
                new GbdcDocumentFallback().findCandidates(DETAIL, HTML);

        assertThat(candidates.getFirst().sourceUrl()).startsWith("https://www.gbdc.co.kr/common/download.do?");
    }

    @Test
    void 모집공고_PDF_를_고른다() {
        List<LocatedDocument> found = locator().rank(new GbdcDocumentFallback().findCandidates(DETAIL, HTML));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).endsWith("모집공고.pdf");
        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void 같은_모집공고라도_HWP_보다_PDF_를_고른다() {
        List<LocatedDocument> found = locator().rank(new GbdcDocumentFallback().findCandidates(DETAIL, HTML));

        assertThat(found.getFirst().fileName()).doesNotEndWith(".hwp");
    }

    @Test
    void 주택목록_평면도_제출서류양식은_제외된다() {
        // maxDocuments 를 넉넉히 줘도 부속자료는 하나도 남지 않아야 한다
        var all = new NoticeDocumentLocator(documentClient,
                new ApplicationGuideProperties(null, null, null, 0, 10, 0, 0, null),
                List.of(new GbdcDocumentFallback()))
                .rank(new GbdcDocumentFallback().findCandidates(DETAIL, HTML));

        assertThat(all).hasSize(2);   // 모집공고 pdf + hwp 뿐
        assertThat(all).allSatisfy(d -> assertThat(d.fileName()).contains("모집공고"));
    }

    @Test
    void GBDC_공고는_source_코드로_맡는다() {
        assertThat(new GbdcDocumentFallback().supports("GBDC", DETAIL)).isTrue();
        assertThat(new GbdcDocumentFallback().supports("MYHOME", DETAIL)).isTrue();   // 호스트로도 잡는다
        assertThat(new GbdcDocumentFallback().supports("LH", URI.create("https://apply.lh.or.kr/x"))).isFalse();
    }

    // ---------- 수집기(PR #35 GbdcHousingDetailSource)가 저장한 DB 첨부 경로 ----------

    /**
     * GbdcHousingDetailSource.attachmentType() 이 실제로 붙이는 값.
     * 수집기가 한 번 돌고 난 뒤에는 폴백이 아니라 이 경로로 공고문을 찾는다.
     */
    private static GuideAttachment saved(long id, String type, String fileName) {
        return new GuideAttachment(id, type, fileName,
                "https://www.gbdc.co.kr/common/download.do?fileVirtualPath=/x/" + id + "&fileOrgName=" + fileName);
    }

    /** notice 58 의 첨부 6건을 수집기가 분류한 그대로 */
    private static List<GuideAttachment> collectedAttachments() {
        return List.of(
                saved(1, "ANNOUNCEMENT", "1. 2026년 영주시 드림주택(청년 매입임대주택) 입주자 및 예비입주자 모집공고.hwp"),
                saved(2, "ANNOUNCEMENT", "1-1. 2026년 영주시 드림주택(청년 매입임대주택) 입주자 및 예비입주자 모집공고.pdf"),
                saved(3, "HOUSING_LIST", "2. 영주시 드림주택(청년 매입임대주택) 공급대상 주택목록(20260922).xlsx"),
                saved(4, "HOUSING_LIST", "2-1. 영주시 드림주택(청년 매입임대주택) 공급대상 주택목록(20260922).pdf"),
                saved(5, "OTHER", "3. [신청서류] 청년 매입임대주택 제출서류 양식(경상북도개발공사).hwp"),
                saved(6, "FLOOR_PLAN_OR_PHOTO", "[별첨] 영주시 드림주택(청년 매입임대주택) 평면도(출입구 확인용).pdf"));
    }

    @Test
    void 수집기가_저장한_첨부에서_모집공고_PDF_를_고른다() {
        List<LocatedDocument> found = locator().select(collectedAttachments());

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).endsWith("모집공고.pdf");
        assertThat(found.getFirst().format()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void 수집기_경로에서도_주택목록_평면도_서식은_모두_제외된다() {
        var all = new NoticeDocumentLocator(documentClient,
                new ApplicationGuideProperties(null, null, null, 0, 10, 0, 0, null),
                List.of(new GbdcDocumentFallback()))
                .select(collectedAttachments());

        assertThat(all).hasSize(2);   // 모집공고 pdf + hwp 뿐
        assertThat(all).allSatisfy(d -> assertThat(d.fileName()).contains("모집공고"));
    }

    /**
     * FLOOR_PLAN_OR_PHOTO 는 PR #35 가 새로 쓰기 시작한 유형이다.
     * 파일명에 '평면'·'도면'·'사진' 이 없어도 유형만으로 걸러져야 한다.
     */
    @Test
    void 도면_유형은_파일명에_단서가_없어도_제외된다() {
        List<LocatedDocument> found = locator().select(List.of(
                saved(7, "FLOOR_PLAN_OR_PHOTO", "붙임 공고 참고자료.pdf")));

        assertThat(found).isEmpty();
    }

    /**
     * 수집기가 한 번이라도 공고문을 저장했으면 상세 페이지를 다시 읽지 않는다.
     * 폴백은 DB 에 쓸 만한 첨부가 없을 때만 동작하는 안전망이다.
     */
    @Test
    void 수집기가_저장했으면_상세페이지를_다시_읽지_않는다() {
        var context = new GuideRequestContext(
                new ApplicationGuideResponse.NoticeHeader(58L, "영주시 드림주택", "경상북도개발공사", DETAIL.toString()),
                "GBDC", null, null, collectedAttachments(),
                new GuideUserContext(24, false, 1, true, StudentStatus.NONE),
                LocalDateTime.now(), null);

        List<LocatedDocument> found = locator().locate(context);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).endsWith("모집공고.pdf");
        verifyNoInteractions(documentClient);
    }
}
