package com.zipick.external.housingdetail;

import com.zipick.external.sh.ShPdfHousingParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GbdcHousingDetailSourceTest {

    @Test
    void GBDC_AI_성공한_첨부는_변경없으면_재분석하지_않는다() throws Exception {
        byte[] pdf;
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage()); doc.save(out); pdf = out.toByteArray();
        }
        String html = "<a onclick=\"pdfViewr('모집공고.pdf','pdf','x','/download?id=1')\">공고</a>";
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return html; }
            @Override public byte[] getBytes(URI uri) { return pdf; }
        };
        var testSource = new GbdcHousingDetailSource(client, new LhSpreadsheetHousingParser(),
                new LhPdfHousingParser(), new LhHwpHousingParser(new LhPdfHousingParser()), new ShPdfHousingParser());
        var history = org.mockito.Mockito.mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        var ai = org.mockito.Mockito.mock(com.zipick.external.ai.GeminiPdfHousingDetailSupport.class);
        testSource.setHistoryRepository(history); testSource.setGeminiSupport(ai);
        org.mockito.Mockito.when(ai.enabled()).thenReturn(true);
        org.mockito.Mockito.when(ai.parserVersion()).thenReturn("TEST");
        var unit = new DetailedHousingUnit("u", null, null, null, "경상북도 경주시 1",
                null, null, null, null, null, null, null, null, null, null);
        org.mockito.Mockito.when(ai.supplementWithStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new com.zipick.external.ai.GeminiPdfHousingDetailSupport.SupplementResult(List.of(unit), true, true));
        var n = new com.zipick.notice.dto.NoticeResponse("GBDC", "1", "OPEN", "공고", "GBDC", null,
                null, null, null, null, null, "https://www.gbdc.co.kr/view", null, null, List.of(), List.of());
        var result = testSource.extract(n);
        assertThat(result.parseCheckpoints()).hasSize(1);
        var cp = result.parseCheckpoints().getFirst();
        var success = new com.zipick.notice.domain.DocumentParseHistory(cp.documentKey(), cp.parserVersion());
        success.markSuccess(cp.size(), cp.unitCount());
        org.mockito.Mockito.when(history.findByDocumentKeyAndParserVersion(cp.documentKey(), cp.parserVersion()))
                .thenReturn(java.util.Optional.of(success));
        assertThat(testSource.extract(n).preservesExistingHousings()).isTrue();
    }

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
