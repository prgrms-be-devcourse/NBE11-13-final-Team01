package com.zipick.external.ai;

import java.util.Arrays;

/** 한 호출에 함께 보낼 수 있는 PDF 원문이다. 문서 경계는 그대로 유지한다. */
public record GeminiPdfDocument(
        String documentId,
        String agency,
        String noticeTitle,
        String fileName,
        byte[] bytes,
        Integer expectedUnitCountHint
) {
    public GeminiPdfDocument(
            String documentId,
            String agency,
            String noticeTitle,
            String fileName,
            byte[] bytes
    ) {
        this(documentId, agency, noticeTitle, fileName, bytes, null);
    }

    public GeminiPdfDocument {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId가 필요합니다.");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("PDF 원문이 필요합니다.");
        if (expectedUnitCountHint != null && expectedUnitCountHint < 0) {
            throw new IllegalArgumentException("예상 공급 행 수는 0 이상이어야 합니다.");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    byte[] rawBytes() {
        return bytes;
    }
}
