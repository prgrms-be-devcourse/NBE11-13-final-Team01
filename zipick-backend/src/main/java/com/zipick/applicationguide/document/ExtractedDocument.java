package com.zipick.applicationguide.document;

import java.util.List;

/** 공고문 한 건에서 뽑아낸 텍스트 전체. 아직 줄이기 전이다 */
public record ExtractedDocument(
        String fileName,
        DocumentFormat format,
        List<DocumentBlock> blocks
) {
    public ExtractedDocument {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public int totalChars() {
        return blocks.stream().mapToInt(DocumentBlock::length).sum();
    }

    public int blockCount() {
        return blocks.size();
    }
}
