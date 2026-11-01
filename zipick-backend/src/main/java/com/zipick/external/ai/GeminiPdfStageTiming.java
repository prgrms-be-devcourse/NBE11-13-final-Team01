package com.zipick.external.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** AI 수집 한 회차의 PDF 준비와 모델 응답 대기를 같은 실행 스레드에서 집계합니다. */
public final class GeminiPdfStageTiming implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiPdfStageTiming.class);
    private static final ThreadLocal<GeminiPdfStageTiming> CURRENT = new ThreadLocal<>();
    private final GeminiPdfStageTiming previous;
    private long inspectNanos;
    private long splitNanos;
    private long requestNanos;
    private long apiNanos;
    private long pageFetchNanos;
    private long attachmentNanos;
    private long basicParserNanos;
    private int requests;
    private int pageFetchCount;
    private int attachmentCount;

    private GeminiPdfStageTiming() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static GeminiPdfStageTiming start() { return new GeminiPdfStageTiming(); }
    static void inspected(long nanos) { var current = CURRENT.get(); if (current != null) current.inspectNanos += nanos; }
    static void split(long nanos) { var current = CURRENT.get(); if (current != null) current.splitNanos += nanos; }
    static void prepared(long nanos) { var current = CURRENT.get(); if (current != null) current.requestNanos += nanos; }
    static void apiWait(long nanos) {
        var current = CURRENT.get();
        if (current != null) { current.apiNanos += nanos; current.requests++; }
    }

    public static void externalFetch(boolean attachment, long nanos) {
        var current = CURRENT.get();
        if (current == null) return;
        if (attachment) { current.attachmentNanos += nanos; current.attachmentCount++; }
        else { current.pageFetchNanos += nanos; current.pageFetchCount++; }
    }

    public static <T> T measureBasicParser(java.util.function.Supplier<T> action) {
        long started = System.nanoTime();
        try { return action.get(); }
        finally {
            var current = CURRENT.get();
            if (current != null) current.basicParserNanos += System.nanoTime() - started;
        }
    }

    @Override public void close() {
        log.info("AI PDF 누적 시간: 기관페이지조회초={}, 페이지요청수={}, 첨부다운로드초={}, 첨부요청수={}, 기본PDF파서초={}, 문서검사초={}, 페이지분할초={}, 요청준비초={}, PDF준비합계초={}, Gemini응답및재시도대기초={}, 분석요청묶음={}",
                seconds(pageFetchNanos), pageFetchCount, seconds(attachmentNanos), attachmentCount, seconds(basicParserNanos),
                seconds(inspectNanos), seconds(splitNanos), seconds(requestNanos),
                seconds(inspectNanos + splitNanos + requestNanos), seconds(apiNanos), requests);
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    private static double seconds(long nanos) { return Math.round(nanos / 10_000_000.0) / 100.0; }
}
