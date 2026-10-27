package com.zipick.external.sh;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.LhHwpHousingParser;
import com.zipick.external.housingdetail.LhPdfHousingParser;
import com.zipick.external.housingdetail.LhSpreadsheetHousingParser;
import com.zipick.external.ai.GeminiPdfHousingDetailSupport;
import com.zipick.external.ai.GeminiPdfHousingExtractor;
import com.zipick.notice.domain.DocumentParseHistory;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.repository.DocumentParseHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShHousingDetailSourceCoverageTest {

    @Test
    void supportsShOrOfficialHostAndRejectsOtherSources() {
        ShHousingDetailSource source = source();

        assertThat(source.supports(notice("SH", null))).isTrue();
        assertThat(source.supports(notice("MYHOME", "https://www.i-sh.co.kr/app/notice"))).isTrue();
        assertThat(source.supports(notice("MYHOME", null))).isFalse();
        assertThat(source.supports(notice("MYHOME", "https://example.com/notice"))).isFalse();
    }

    @Test
    void downloadsSpreadsheetParsesUnitsAndStoresHistory() {
        ExternalDocumentClient client = mock(ExternalDocumentClient.class);
        LhSpreadsheetHousingParser spreadsheet = mock(LhSpreadsheetHousingParser.class);
        DocumentParseHistoryRepository history = mock(DocumentParseHistoryRepository.class);
        ShHousingDetailSource active = new ShHousingDetailSource(
                client, mock(ShPdfHousingParser.class), spreadsheet,
                mock(LhPdfHousingParser.class), mock(LhHwpHousingParser.class), history
        );
        String html = """
                <script>initParam.downList = [
                  {"brdId":"GS0401","seq":"100","fileSeq":"1","fileSize":100,
                   "oriFileNm":"주택목록(상세).xlsx","fileTp":"A"},
                  {"brdId":"GS0401","seq":"100","fileSeq":"2","fileSize":50,
                   "oriFileNm":"안내.txt","fileTp":"A"}
                ];</script>
                """;
        when(client.getText(any())).thenReturn(html);
        when(client.getBytes(any())).thenReturn(new byte[]{1, 2});
        when(history.findByDocumentKeyAndParserVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(spreadsheet.parse(any(), any())).thenReturn(List.of(unit()));

        var result = active.extract(notice("SH", "https://www.i-sh.co.kr/app/notice/100"));

        assertThat(result.housings()).hasSize(1);
        assertThat(result.attachments()).hasSize(2);
        assertThat(result.attachments().getFirst().attachmentType()).isEqualTo("HOUSING_LIST_DETAIL");
        verify(history).save(any(DocumentParseHistory.class));
    }

    @Test
    void fallsBackFromStructuredPdfAndSkipsPreviouslyParsedDocument() {
        ExternalDocumentClient client = mock(ExternalDocumentClient.class);
        ShPdfHousingParser shPdf = mock(ShPdfHousingParser.class);
        LhPdfHousingParser fallback = mock(LhPdfHousingParser.class);
        DocumentParseHistoryRepository history = mock(DocumentParseHistoryRepository.class);
        ShHousingDetailSource active = new ShHousingDetailSource(
                client, shPdf, mock(LhSpreadsheetHousingParser.class), fallback,
                mock(LhHwpHousingParser.class), history
        );
        String html = """
                <script>initParam.downList = [{"brdId":"G","seq":"1","fileSeq":"1",
                "fileSize":100,"oriFileNm":"입주자 모집공고.pdf","fileTp":"A"}];</script>
                """;
        when(client.getText(any())).thenReturn(html);
        when(client.getBytes(any())).thenReturn(new byte[]{1});
        when(history.findByDocumentKeyAndParserVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(shPdf.parse(any(), any())).thenReturn(List.of());
        when(fallback.parse(any(), any())).thenReturn(List.of(unit()));

        assertThat(active.extract(notice("SH", "https://www.i-sh.co.kr/detail")).housings()).hasSize(1);

        DocumentParseHistory success = new DocumentParseHistory("key", "version");
        success.markSuccess(100L, 1);
        when(history.findByDocumentKeyAndParserVersion(anyString(), anyString()))
                .thenReturn(Optional.of(success));
        assertThat(active.extract(notice("SH", "https://www.i-sh.co.kr/detail")).housings()).isEmpty();
    }

    @Test
    void AI가_불완전하면_기존파서결과는_사용하되_AI성공이력은_남기지않는다() {
        ExternalDocumentClient client = mock(ExternalDocumentClient.class);
        LhPdfHousingParser fallback = mock(LhPdfHousingParser.class);
        DocumentParseHistoryRepository history = mock(DocumentParseHistoryRepository.class);
        GeminiPdfHousingDetailSupport gemini = mock(GeminiPdfHousingDetailSupport.class);
        ShHousingDetailSource active = new ShHousingDetailSource(
                client, mock(ShPdfHousingParser.class), mock(LhSpreadsheetHousingParser.class), fallback,
                mock(LhHwpHousingParser.class), history
        );
        active.setGeminiSupport(gemini);
        String html = """
                <script>initParam.downList = [{"brdId":"G","seq":"1","fileSeq":"1",
                "fileSize":100,"oriFileNm":"입주자 모집공고.pdf","fileTp":"A"}];</script>
                """;
        when(client.getText(any())).thenReturn(html);
        when(client.getBytes(any())).thenReturn(new byte[]{1});
        when(history.findByDocumentKeyAndParserVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(fallback.parse(any(), any())).thenReturn(List.of(unit()));
        when(gemini.enabled()).thenReturn(true);
        when(gemini.parserVersion()).thenReturn(GeminiPdfHousingExtractor.PROMPT_VERSION);
        when(gemini.supplementWithStatus(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new GeminiPdfHousingDetailSupport.SupplementResult(
                        List.of(unit()), false, false));

        var result = active.extract(notice("SH", "https://www.i-sh.co.kr/detail"));

        assertThat(result.housings()).hasSize(1);
        verify(history, never()).save(any(DocumentParseHistory.class));
    }

    @Test
    void V17_성공이력이_있어도_V18은_건너뛰지_않고_다시_처리한다() {
        ExternalDocumentClient client = mock(ExternalDocumentClient.class);
        DocumentParseHistoryRepository history = mock(DocumentParseHistoryRepository.class);
        GeminiPdfHousingDetailSupport gemini = mock(GeminiPdfHousingDetailSupport.class);
        ShHousingDetailSource active = new ShHousingDetailSource(
                client, mock(ShPdfHousingParser.class), mock(LhSpreadsheetHousingParser.class),
                mock(LhPdfHousingParser.class), mock(LhHwpHousingParser.class), history
        );
        active.setGeminiSupport(gemini);
        String html = """
                <script>initParam.downList = [{"brdId":"G","seq":"1","fileSeq":"1",
                "fileSize":100,"oriFileNm":"입주자 모집공고.pdf","fileTp":"A"}];</script>
                """;
        DocumentParseHistory old = new DocumentParseHistory("old", "SH_DIRECT_HOUSING_V17_RELEVANT_PAGE_COMPLETE_COLUMNS");
        old.markSuccess(100L, 1);
        when(client.getText(any())).thenReturn(html);
        when(client.getBytes(any())).thenReturn(new byte[]{1});
        when(history.findByDocumentKeyAndParserVersion(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1, String.class).contains("V17")
                        ? Optional.of(old) : Optional.empty());
        when(gemini.enabled()).thenReturn(true);
        when(gemini.parserVersion()).thenReturn(GeminiPdfHousingExtractor.PROMPT_VERSION);
        when(gemini.supplementWithStatus(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new GeminiPdfHousingDetailSupport.SupplementResult(List.of(unit()), true, true));

        assertThat(active.extract(notice("SH", "https://www.i-sh.co.kr/detail")).housings()).hasSize(1);

        verify(history, times(2)).findByDocumentKeyAndParserVersion(
                anyString(), eq("SH_" + GeminiPdfHousingExtractor.PROMPT_VERSION));
        verify(client).getBytes(any());
        verify(history).save(any(DocumentParseHistory.class));
    }

    @Test
    void reportsMalformedAttachmentJsonAndIgnoresUnknownDocuments() {
        ShHousingDetailSource source = source();

        assertThat(source.findAttachments("본문만 있음")).isEmpty();
        assertThatThrownBy(() -> source.findAttachments("initParam.downList = [{broken}];"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(source.housingCandidates(List.of(
                attachment("1", "개인정보동의서.pdf"), attachment("2", null)
        ))).isEmpty();
    }

    private ShHousingDetailSource source() {
        return new ShHousingDetailSource(
                mock(ExternalDocumentClient.class), mock(ShPdfHousingParser.class),
                mock(LhSpreadsheetHousingParser.class), mock(LhPdfHousingParser.class),
                mock(LhHwpHousingParser.class), mock(DocumentParseHistoryRepository.class)
        );
    }

    private ShAttachment attachment(String fileSeq, String name) {
        return new ShAttachment("GS0401", "100", fileSeq, 100L, name, "A");
    }

    private NoticeResponse notice(String noticeSource, String url) {
        return new NoticeResponse(noticeSource, "n1", "공고중", "공고", "SH", null,
                null, null, null, null, null, url, null, null, List.of(), List.of());
    }

    private DetailedHousingUnit unit() {
        return new DetailedHousingUnit(
                "u1", "단지", "서울", "강남구", "서울 강남구 테헤란로 1",
                "101동", "101호", 1, new BigDecimal("39.5"), 2,
                "아파트", "국민임대", 10_000_000L, 300_000L, "{}"
        );
    }
}
