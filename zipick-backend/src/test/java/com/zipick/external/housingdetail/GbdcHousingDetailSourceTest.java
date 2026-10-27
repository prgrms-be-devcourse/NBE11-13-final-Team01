package com.zipick.external.housingdetail;

import com.zipick.external.sh.ShPdfHousingParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GbdcHousingDetailSourceTest {

    private final GbdcHousingDetailSource source = new GbdcHousingDetailSource(
            null, new LhSpreadsheetHousingParser(), new LhPdfHousingParser(),
            new LhHwpHousingParser(new LhPdfHousingParser()), new ShPdfHousingParser()
    );

    @Test
    void readsViewerAttachmentsAndPrefersHousingSpreadsheet() {
        String html = """
                <a href="javascript:void(0)" onclick="pdfViewr('1. 모집공고.pdf', 'pdf', '/private/a.pdf', '/common/download.do?a=1&amp;b=2')">공고</a>
                <a href="javascript:void(0)" onclick="pdfViewr('2. 공급대상 주택목록.xlsx', 'xlsx', '/private/b.xlsx', '/common/download.do?a=3&amp;b=4')">목록</a>
                <a href="javascript:void(0)" onclick="pdfViewr('3. 신청서류 양식.hwp', 'hwp', '/private/c.hwp', '/common/download.do?a=5')">양식</a>
                """;

        List<GbdcAttachment> attachments = source.findAttachments(
                html, URI.create("https://www.gbdc.co.kr/boardview/view.do?id=1"));

        assertThat(attachments).hasSize(3);
        assertThat(attachments.get(1).downloadUrl()).isEqualTo(
                "https://www.gbdc.co.kr/common/download.do?a=3&b=4");
        assertThat(source.housingCandidates(attachments)).extracting(GbdcAttachment::fileName)
                .containsExactly("2. 공급대상 주택목록.xlsx", "1. 모집공고.pdf");
    }
}
