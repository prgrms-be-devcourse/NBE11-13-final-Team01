package com.zipick.external.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 같은 공고의 문서를 우선 묶고, 페이지·파일 크기·문서 수 한도 안에서 호출 수를 줄인다. */
final class GeminiPdfBatchPlanner {

    private final GeminiPdfParserProperties properties;

    GeminiPdfBatchPlanner(GeminiPdfParserProperties properties) {
        this.properties = properties;
    }

    GeminiPdfBatchPlan plan(List<GeminiPdfDocument> documents) {
        if (documents == null || documents.isEmpty()) return new GeminiPdfBatchPlan(List.of(), 0, 0);
        List<DocumentSize> measured = documents.stream()
                .map(this::measure)
                .sorted(Comparator.comparing((DocumentSize value) -> safe(value.document().noticeTitle()))
                        .thenComparing(Comparator.comparingInt(DocumentSize::pages).reversed()))
                .toList();
        List<MutableBatch> batches = new ArrayList<>();
        for (DocumentSize document : measured) {
            MutableBatch selected = batches.stream()
                    .filter(batch -> batch.sameNotice(document) && batch.canAdd(document, properties))
                    .findFirst()
                    .orElseGet(() -> batches.stream()
                            .filter(batch -> batch.canAdd(document, properties))
                            .findFirst().orElse(null));
            if (selected == null) {
                selected = new MutableBatch();
                batches.add(selected);
            }
            selected.add(document);
        }
        List<List<GeminiPdfDocument>> result = batches.stream()
                .map(batch -> batch.documents.stream().map(DocumentSize::document).toList())
                .toList();
        return new GeminiPdfBatchPlan(result, documents.size(), result.size());
    }

    private DocumentSize measure(GeminiPdfDocument document) {
        byte[] bytes = document.rawBytes();
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int pages = pdf.getNumberOfPages();
            if (pages > properties.maxPagesPerRequest()) {
                throw new IllegalArgumentException("단일 PDF가 요청 페이지 제한을 초과했습니다: " + document.fileName());
            }
            return new DocumentSize(document, pages, bytes.length);
        } catch (IOException exception) {
            throw new IllegalArgumentException("올바른 PDF가 아닙니다: " + document.fileName(), exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record DocumentSize(GeminiPdfDocument document, int pages, long bytes) { }

    private static final class MutableBatch {
        private final List<DocumentSize> documents = new ArrayList<>();
        private int pages;
        private long bytes;

        boolean sameNotice(DocumentSize candidate) {
            return !documents.isEmpty() && java.util.Objects.equals(
                    documents.getFirst().document().noticeTitle(), candidate.document().noticeTitle()
            );
        }

        boolean canAdd(DocumentSize candidate, GeminiPdfParserProperties properties) {
            return documents.size() < properties.maxDocumentsPerRequest()
                    && pages + candidate.pages() <= properties.maxPagesPerRequest()
                    && bytes + candidate.bytes() <= properties.maxFileBytes();
        }

        void add(DocumentSize document) {
            documents.add(document);
            pages += document.pages();
            bytes += document.bytes();
        }
    }
}
