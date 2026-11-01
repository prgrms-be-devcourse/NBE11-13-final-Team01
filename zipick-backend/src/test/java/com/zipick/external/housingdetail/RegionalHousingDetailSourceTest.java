package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RegionalHousingDetailSourceTest {
    private final RegionalHousingDetailSource source = new RegionalHousingDetailSource(
            mock(ExternalDocumentClient.class), mock(LhPdfHousingParser.class),
            mock(LhHwpHousingParser.class), mock(LhSpreadsheetHousingParser.class));

    @Test
    void 압축문서_일부가_실패해도_성공분은_유지하고_기존_주택을_보존한다() throws Exception {
        var client = mock(ExternalDocumentClient.class);
        var pdf = mock(LhPdfHousingParser.class);
        var parser = new RegionalHousingDetailSource(client, pdf, mock(LhHwpHousingParser.class), mock(LhSpreadsheetHousingParser.class));
        String url = "https://www.jpdc.co.kr/open/news/notice.htm?act=view&seq=24224";
        var notice = new com.zipick.notice.dto.NoticeResponse("MYHOME", "1", "공고중", "모집공고", null, null,
                null, null, null, null, null, url, null, null, java.util.List.of(), java.util.List.of());
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String name : java.util.List.of("공고문1.pdf", "공고문2.pdf")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(1); zip.closeEntry();
            }
        }
        org.mockito.Mockito.when(client.getText(URI.create(url))).thenReturn(
                "<a href='/open/news/notice.htm?act=download&amp;seq=24224&amp;no=1'>공고문.zip</a>");
        org.mockito.Mockito.when(client.getBytes(org.mockito.ArgumentMatchers.any())).thenReturn(bytes.toByteArray());
        var unit = new DetailedHousingUnit("test", null, null, null, "제주특별자치도 서귀포시 신중로 46",
                null, null, null, null, null, null, null, null, null, null);
        org.mockito.Mockito.when(pdf.parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of(unit)).thenThrow(new IllegalStateException("읽기 실패"));
        var result = parser.extract(notice);
        assertThat(result.housings()).containsExactly(unit);
        assertThat(result.preservesExistingHousings()).isTrue();
        assertThat(result.parseCheckpoints()).isEmpty();
    }

    @Test
    void discoversAttachmentsFromCapturedRealPages() throws Exception {
        Path directory = Path.of("tmp", "regional-pages");
        Assumptions.assumeTrue(Files.isRegularFile(directory.resolve("0.html")));
        String[] urls = {
                "https://www.gmcc.co.kr/board.es?mid=a10402000000&bid=0018&act=view&list_no=19515",
                "https://www.cbdc.co.kr/zboard/read.do?searchWrd=&pageIndex=1&lmCode=BBSMSTR_000000000028&searchCnd=&searchCate1=&pd_pkid=7801",
                "https://www.kohom.or.kr/web/mainComm/HM001002002.do?mode=view&p_idx=16068",
                "https://www.gmcc.co.kr/board.es?mid=a10402000000&bid=0018&act=view&list_no=19505",
                "https://www.jpdc.co.kr/open/news/notice.htm?act=view&seq=24224",
                "https://www.cbdc.co.kr/zboard/read.do?searchWrd=&pageIndex=1&lmCode=BBSMSTR_000000000028&searchCnd=&searchCate1=&pd_pkid=6636"
        };
        int[] counts = {2, 4, 2, 2, 1, 4};
        for (int i = 0; i < urls.length; i++) {
            var actual = source.findAttachments(Files.readString(directory.resolve(i + ".html")), URI.create(urls[i]));
            assertThat(actual).as("page %s", i).hasSize(counts[i]);
        }
    }

    @Test
    void discoversRegionalAttachmentsWithoutNavigationLinksOrDuplicateButtons() {
        var gmcc = source.findAttachments("""
                <a href='/boardDownload.es?bid=0018&amp;list_no=19515&amp;seq=1'>주월빛여울채 모집공고문.pdf</a>
                <a href='/boardDownload.es?bid=0018&amp;list_no=19515&amp;seq=1'>다운로드</a>
                <a href='/boardDownload.es?bid=0018&amp;list_no=19515&amp;seq=2'>신청서식.hwpx</a>
                """, URI.create("https://www.gmcc.co.kr/board.es?list_no=19515"));
        assertThat(gmcc).hasSize(2);
        assertThat(gmcc.getFirst().sourceUrl()).contains("list_no=19515&seq=1");

        var cbdc = source.findAttachments("""
                <a href='/zboard/printFileDown.do?atchFileId=FILE_1&amp;fileSn=0'>1 : 1. 모집공고문.hwpx [299300 byte]</a>
                <a href='/zboard/printFileDown.do?atchFileId=FILE_1&amp;fileSn=2'>3 : 팜플렛.pdf [2329131 byte]</a>
                """, URI.create("https://www.cbdc.co.kr/zboard/read.do?pd_pkid=7801"));
        assertThat(cbdc).extracting(a -> a.fileName()).containsExactly("1. 모집공고문.hwpx", "팜플렛.pdf");

        var kohom = source.findAttachments("""
                <a href='/common/download.do;jsessionid=ABC?f=encrypted' class='file'>공고문.pdf</a>
                <a href='/common/download.do;jsessionid=ABC?f=other' class='file'>공고문.pdf</a>
                """, URI.create("https://www.kohom.or.kr/web/view.do?p_idx=16068"));
        assertThat(kohom).hasSize(1);
        assertThat(kohom.getFirst().sourceUrl()).doesNotContain("jsessionid");

        var jpdc = source.findAttachments("""
                <a href='/open/news/notice.htm?act=download&amp;seq=24224&amp;no=1'>1. 공고문 및 신청접수 서류양식.zip (18 MBytes)</a>
                <a href='/open/news/notice.htm?act=download&amp;seq=24238&amp;no=1'>다른 공고.pdf</a>
                """, URI.create("https://www.jpdc.co.kr/open/news/notice.htm?act=view&seq=24224"));
        assertThat(jpdc).extracting(a -> a.fileName()).containsExactly("1. 공고문 및 신청접수 서류양식.zip");
    }
}
