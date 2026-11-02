package com.zipick.external.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiPdfStageTimingTest {
    @Test
    void 실행별_PDF_준비와_API_대기시간을_각각_집계하고_다음_실행에_남기지_않는다() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiPdfStageTiming.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            try (var timing = GeminiPdfStageTiming.start()) {
                GeminiPdfStageTiming.inspected(2_000_000_000L);
                GeminiPdfStageTiming.split(3_000_000_000L);
                GeminiPdfStageTiming.prepared(1_000_000_000L);
                GeminiPdfStageTiming.apiWait(8_000_000_000L);
                GeminiPdfStageTiming.externalFetch(true, 4_000_000_000L);
            }
            try (var ignored = GeminiPdfStageTiming.start()) { }
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains(
                    "첨부다운로드초=4.0", "첨부요청수=1", "PDF준비합계초=6.0",
                    "Gemini응답및재시도대기초=8.0", "분석요청묶음=1");
            assertThat(appender.list.getLast().getFormattedMessage()).contains(
                    "PDF준비합계초=0.0", "Gemini응답및재시도대기초=0.0", "분석요청묶음=0");
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }
}
