package com.zipick.applicationguide.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.GuideUnavailableReason;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 공고문에서 텍스트 뽑기.
 *
 * 쪽 단위로 끊는 이유는 출처에 실제 쪽수를 붙이기 위해서다.
 * 실제 공고문에는 표지·간지처럼 글자가 거의 없는 쪽이 섞여 있어 그런 쪽은 버린다.
 */
@ExtendWith(MockitoExtension.class)
class NoticeDocumentTextExtractorTest {

    @Mock ExternalDocumentClient documentClient;

    private NoticeDocumentTextExtractor extractor(int minExtractedChars) {
        return new NoticeDocumentTextExtractor(documentClient,
                new ApplicationGuideProperties(null, null, null, 30_000, 1, 1, minExtractedChars, null));
    }

    /** 한 줄짜리 쪽을 이어 붙인 PDF 를 만든다 (표준 폰트라 본문은 영문으로 쓴다) */
    private static byte[] pdf(String... pageTexts) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (!text.isEmpty()) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(40, 700);
                        stream.showText(text);
                        stream.endText();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String longLine(String word) {
        return (word + " ").repeat(20);
    }

    @Test
    void PDF_는_쪽마다_하나씩_덩어리를_만들고_쪽수를_기억한다() throws Exception {
        byte[] bytes = pdf(longLine("first"), longLine("second"));

        ExtractedDocument document = extractor(1).extractPdf(bytes, "모집공고.pdf");

        assertThat(document.blockCount()).isEqualTo(2);
        assertThat(document.blocks().get(0).page()).isEqualTo(1);
        assertThat(document.blocks().get(1).page()).isEqualTo(2);
        assertThat(document.blocks().get(0).text()).contains("first");
        assertThat(document.format()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void 글자가_거의_없는_쪽은_버리고_쪽수는_원래대로_유지한다() throws Exception {
        // 표지·간지는 추출하면 쪽 번호 한 글자만 남는다. 문맥으로 끌려 들어오면 자리만 차지한다
        byte[] bytes = pdf("", longLine("content"), "3");

        ExtractedDocument document = extractor(1).extractPdf(bytes, "모집공고.pdf");

        assertThat(document.blockCount()).isEqualTo(1);
        assertThat(document.blocks().getFirst().page()).isEqualTo(2);
    }

    @Test
    void 글자가_너무_적으면_스캔_문서로_보고_포기한다() throws Exception {
        // 실제로 SH 매입임대 모집공고문 중에 폰트 없이 JPEG 20장으로만 된 PDF 가 있었다.
        // OCR 은 이번 범위가 아니므로 사용자에게 원문 확인을 안내한다.
        byte[] bytes = pdf("");
        when(documentClient.getBytes(URI.create("https://example.test/scan.pdf"))).thenReturn(bytes);
        var document = new LocatedDocument("scan.pdf", "https://example.test/scan.pdf", DocumentFormat.PDF, 100);

        assertThatThrownBy(() -> extractor(1_000).extract(document))
                .isInstanceOf(GuideDocumentException.class)
                .extracting(e -> ((GuideDocumentException) e).getReason())
                .isEqualTo(GuideUnavailableReason.SCANNED_DOCUMENT);
    }

    @Test
    void 내려받지_못하면_DOWNLOAD_FAILED_로_끝난다() {
        when(documentClient.getBytes(URI.create("https://example.test/x.pdf")))
                .thenThrow(new IllegalStateException("외부 문서 요청 실패: 404"));
        var document = new LocatedDocument("x.pdf", "https://example.test/x.pdf", DocumentFormat.PDF, 100);

        assertThatThrownBy(() -> extractor(1).extract(document))
                .isInstanceOf(GuideDocumentException.class)
                .extracting(e -> ((GuideDocumentException) e).getReason())
                .isEqualTo(GuideUnavailableReason.DOWNLOAD_FAILED);
    }

    @Test
    void 공급기관이_200_으로_오류_본문을_주면_다운로드_실패로_본다() {
        // 실제로 GH 가 .pdf 주소에 277바이트짜리 XML "err.파일.다운로드.실패" 를 200 으로 돌려줬다.
        // 그대로 PDFBox 에 넘기면 "파일을 읽지 못했다" 가 되는데 실제 원인은 다운로드 실패다.
        byte[] errorXml = "<?xml version=\"1.0\"?><Root><ErrorMsg>err</ErrorMsg></Root>"
                .getBytes(StandardCharsets.UTF_8);
        when(documentClient.getBytes(URI.create("https://example.test/a.pdf"))).thenReturn(errorXml);
        var document = new LocatedDocument("모집공고.pdf", "https://example.test/a.pdf", DocumentFormat.PDF, 110);

        assertThatThrownBy(() -> extractor(1).extract(document))
                .isInstanceOf(GuideDocumentException.class)
                .extracting(e -> ((GuideDocumentException) e).getReason())
                .isEqualTo(GuideUnavailableReason.DOWNLOAD_FAILED);
    }

    @Test
    void 열_수_없는_파일이면_EXTRACT_FAILED_로_끝난다() {
        byte[] broken = "이건 PDF 가 아니다".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor(1).extractPdf(broken, "깨진공고문.pdf"))
                .isInstanceOf(GuideDocumentException.class)
                .extracting(e -> ((GuideDocumentException) e).getReason())
                .isEqualTo(GuideUnavailableReason.EXTRACT_FAILED);
    }

    @Test
    void HWP_는_빈_줄로_덩어리를_나누고_첫_줄을_소제목으로_쓴다() {
        // Tika 는 HWP 에서 쪽 경계를 주지 않는다. 쪽수 대신 소제목으로 출처를 표시한다.
        // (여기서는 Tika 가 똑같이 처리하는 평문 바이트로 분할 규칙만 확인한다)
        String text = """
                04 제출서류
                주민등록등본과 가족관계증명서를 제출하여야 합니다.

                05 신청방법
                인터넷청약으로만 접수하며 방문접수는 받지 않습니다.
                """;

        ExtractedDocument document = extractor(1).extractHwp(text.getBytes(StandardCharsets.UTF_8), "모집공고.hwp");

        assertThat(document.blockCount()).isEqualTo(2);
        assertThat(document.blocks().get(0).section()).isEqualTo("04 제출서류");
        assertThat(document.blocks().get(1).section()).isEqualTo("05 신청방법");
        assertThat(document.blocks().get(0).page()).isNull();
        assertThat(document.format()).isEqualTo(DocumentFormat.HWP);
    }

    @Test
    void HWP_의_아주_긴_덩어리는_잘라_쪽처럼_만든다() {
        // 빈 줄이 거의 없는 문서가 통째로 한 덩어리가 되면 그 하나로 입력 예산을 다 써 버린다
        String line = "신청자격은 무주택 세대구성원이어야 합니다.\n";
        ExtractedDocument document = extractor(1)
                .extractHwp(line.repeat(300).getBytes(StandardCharsets.UTF_8), "모집공고.hwp");

        assertThat(document.blockCount()).isGreaterThan(1);
        assertThat(document.blocks()).allSatisfy(block -> assertThat(block.length()).isLessThanOrEqualTo(3_100));
    }

    @Test
    void HWPX_는_내려받기_전에_지원하지_않는_형식으로_끝낸다() {
        // Tika 4.1.0 의 한글 파서는 HwpV5Parser 뿐이라 HWPX(zip+xml)는 제대로 읽히지 않는다.
        // 받아 봐야 버릴 바이트이므로 다운로드 전에 끊고, 사용자에게는 실제 원인을 알린다.
        var document = new LocatedDocument(
                "은빛주택 모집 공고문.hwpx", "https://example.test/a.hwpx", DocumentFormat.HWPX, 160);

        assertThatThrownBy(() -> extractor(1).extract(document))
                .isInstanceOf(GuideDocumentException.class)
                .satisfies(e -> assertThat(((GuideDocumentException) e).getReason())
                        .isEqualTo(GuideUnavailableReason.UNSUPPORTED_DOCUMENT_FORMAT));

        verifyNoInteractions(documentClient);
    }
}
