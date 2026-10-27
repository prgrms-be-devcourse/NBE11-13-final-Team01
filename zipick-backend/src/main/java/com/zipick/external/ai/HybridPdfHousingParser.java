package com.zipick.external.ai;

import org.springframework.stereotype.Component;

import java.util.List;

/** 전체 PDF의 의미를 Gemini가 직접 해석하고 상세 주택을 정규화한다. */
@Component
public class HybridPdfHousingParser {

    private final GeminiPdfHousingExtractor extractor;

    public HybridPdfHousingParser(GeminiPdfHousingExtractor extractor) {
        this.extractor = extractor;
    }

    public GeminiPdfExtractionResult parse(
            byte[] pdfBytes,
            String documentKey,
            String agency,
            String title
    ) {
        return parse(pdfBytes, documentKey, agency, title, null);
    }

    public GeminiPdfExtractionResult parse(
            byte[] pdfBytes,
            String documentKey,
            String agency,
            String title,
            Integer expectedUnitCountHint
    ) {
        return extractor.extract(List.of(new GeminiPdfDocument(
                documentKey, agency, title, title + ".pdf", pdfBytes, expectedUnitCountHint
        )));
    }

    public GeminiPdfBatchExtractionResult parse(List<GeminiPdfDocument> documents) {
        return extractor.extractAll(documents);
    }
}
