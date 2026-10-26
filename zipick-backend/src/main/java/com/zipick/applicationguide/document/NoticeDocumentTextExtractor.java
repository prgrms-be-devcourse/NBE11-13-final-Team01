package com.zipick.applicationguide.document;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.GuideUnavailableReason;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공고문에서 텍스트만 뽑는다.
 *
 * <h3>기존 파서를 쓰지 않은 이유</h3>
 * LhPdfHousingParser / LhHwpHousingParser 도 PDFBox·Tika 로 텍스트를 뽑지만, 뽑자마자
 * 주소·보증금 정규식으로 넘어가고 <b>쪽 경계를 버린다</b>. 목적이 "정형 데이터 추출"이라 그렇다.
 * 이 기능은 반대로 쪽 경계가 필요하다(출처에 쪽수를 붙여야 한다).
 * 공고 수집 담당 파서를 공용으로 리팩토링하면 수집 품질에 회귀 위험이 생기므로,
 * 라이브러리(PDFBox·Tika)만 그대로 쓰고 추출기는 따로 두었다. 새 의존성은 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeDocumentTextExtractor {

    /**
     * HWP 한 덩어리의 최대 길이. 빈 줄이 거의 없는 문서가 통째로 한 덩어리가 되면
     * 그 하나로 입력 예산을 다 써 버려 선별이 의미가 없어진다. 쪽처럼 잘라 둔다.
     */
    private static final int MAX_HWP_BLOCK_CHARS = 3_000;
    private static final int MAX_SECTION_LABEL_CHARS = 40;

    /**
     * 이보다 글자가 적은 덩어리는 버린다.
     * 공고문에는 표지·간지·이미지만 있는 쪽이 섞여 있는데, 추출하면 쪽 번호 한 글자만 남는다.
     * 그대로 두면 앞뒤 문맥으로 끌려 들어와 SOURCE 자리만 차지하고 AI 에게는 아무 정보도 주지 못한다.
     */
    private static final int MIN_BLOCK_CHARS = 30;

    private final ExternalDocumentClient documentClient;
    private final ApplicationGuideProperties properties;

    /** PDF 파일은 항상 이 네 글자로 시작한다 */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    /** 내려받아 텍스트를 뽑는다. 읽지 못하면 GuideDocumentException */
    public ExtractedDocument extract(LocatedDocument document) {
        byte[] bytes = download(document);
        verifyFormat(bytes, document);
        ExtractedDocument extracted = switch (document.format()) {
            case PDF -> extractPdf(bytes, document.fileName());
            case HWP -> extractHwp(bytes, document.fileName());
        };

        if (extracted.totalChars() < properties.minExtractedChars()) {
            // 글자가 거의 없다 = 스캔한 이미지 문서다. OCR 은 이번 범위가 아니다.
            throw new GuideDocumentException(GuideUnavailableReason.SCANNED_DOCUMENT,
                    "공고문에서 읽어낸 글자가 너무 적습니다: " + document.fileName()
                            + " (" + extracted.totalChars() + "자)");
        }
        log.info("공고문 텍스트 추출 완료: file={}, format={}, 덩어리={}개, {}자",
                document.fileName(), document.format(), extracted.blockCount(), extracted.totalChars());
        return extracted;
    }

    private byte[] download(LocatedDocument document) {
        try {
            return documentClient.getBytes(URI.create(document.sourceUrl()));
        } catch (RuntimeException exception) {
            throw new GuideDocumentException(GuideUnavailableReason.DOWNLOAD_FAILED,
                    "공고문을 내려받지 못했습니다: " + document.fileName(), exception);
        }
    }

    /**
     * 내려받은 것이 정말 그 형식의 파일인지 본다.
     *
     * 공급기관 서버가 <b>200 으로 오류 본문을 돌려주는 경우가 실제로 있다.</b>
     * (GH 에서 277바이트짜리 XML "err.파일.다운로드.실패" 가 .pdf 주소로 내려왔다)
     * 그대로 PDFBox 에 넘기면 "파일을 읽지 못했습니다"(EXTRACT_FAILED)가 되는데,
     * 실제 원인은 다운로드 실패라 사용자에게 보여 줄 안내가 달라야 한다.
     */
    private static void verifyFormat(byte[] bytes, LocatedDocument document) {
        if (document.format() != DocumentFormat.PDF) {
            return;   // HWP 는 버전마다 머리 바이트가 달라 Tika 판단에 맡긴다
        }
        if (bytes.length < PDF_MAGIC.length || !startsWith(bytes, PDF_MAGIC)) {
            throw new GuideDocumentException(GuideUnavailableReason.DOWNLOAD_FAILED,
                    "PDF 가 아닌 응답을 받았습니다(공급기관 서버 오류로 보입니다): "
                            + document.fileName() + " (" + bytes.length + "바이트)");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** PDF 는 쪽 단위로 뽑는다. setSortByPosition 은 기존 파서와 같은 설정이다 */
    ExtractedDocument extractPdf(byte[] bytes, String fileName) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            List<DocumentBlock> blocks = new ArrayList<>();
            int pageCount = pdf.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(pdf));
                if (text.length() >= MIN_BLOCK_CHARS) {
                    blocks.add(new DocumentBlock(blocks.size(), page, sectionLabel(text), text));
                }
            }
            return new ExtractedDocument(fileName, DocumentFormat.PDF, blocks);
        } catch (Exception exception) {
            throw new GuideDocumentException(GuideUnavailableReason.EXTRACT_FAILED,
                    "공고문 PDF 를 읽지 못했습니다: " + fileName, exception);
        }
    }

    /**
     * HWP 는 Tika 가 통짜 문자열만 준다. 쪽을 알 수 없으므로 빈 줄로 끊고,
     * 너무 긴 덩어리는 다시 잘라 PDF 의 쪽과 비슷한 크기로 맞춘다.
     */
    ExtractedDocument extractHwp(byte[] bytes, String fileName) {
        String text;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            text = normalize(new Tika().parseToString(input));
        } catch (Exception exception) {
            throw new GuideDocumentException(GuideUnavailableReason.EXTRACT_FAILED,
                    "공고문 HWP 를 읽지 못했습니다: " + fileName, exception);
        }

        List<DocumentBlock> blocks = new ArrayList<>();
        for (String chunk : text.split("\\n\\s*\\n")) {
            for (String piece : splitByLength(chunk.strip())) {
                if (piece.length() >= MIN_BLOCK_CHARS) {
                    blocks.add(new DocumentBlock(blocks.size(), null, sectionLabel(piece), piece));
                }
            }
        }
        return new ExtractedDocument(fileName, DocumentFormat.HWP, blocks);
    }

    /** 긴 덩어리를 줄 경계에서 MAX_HWP_BLOCK_CHARS 이하로 자른다 */
    private static List<String> splitByLength(String text) {
        if (text.length() <= MAX_HWP_BLOCK_CHARS) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\n")) {
            if (current.length() + line.length() + 1 > MAX_HWP_BLOCK_CHARS && !current.isEmpty()) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** 덩어리의 첫 의미 있는 줄. HWP 출처 표시에 쓴다 */
    private static String sectionLabel(String text) {
        for (String line : text.split("\\n")) {
            String trimmed = line.strip();
            if (trimmed.length() >= 2) {
                return trimmed.length() <= MAX_SECTION_LABEL_CHARS
                        ? trimmed
                        : trimmed.substring(0, MAX_SECTION_LABEL_CHARS) + "…";
            }
        }
        return "";
    }

    /** 줄바꿈을 통일하고 빈 줄이 3개 이상 이어지는 것을 줄인다 (토큰 낭비) */
    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\u00a0]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
