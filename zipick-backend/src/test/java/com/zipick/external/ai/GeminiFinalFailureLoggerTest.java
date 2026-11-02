package com.zipick.external.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GeminiFinalFailureLoggerTest {
    @Test
    void 상태코드와_연결오류를_본문없이_기록한다() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiFinalFailureLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            GeminiFinalFailureLogger.logFailure("extraction", new IllegalStateException("status=400 body=private"));
            GeminiFinalFailureLogger.logFailure("extraction", new IllegalStateException("status=429"));
            GeminiFinalFailureLogger.logFailure("normalization", new IllegalStateException("Gemini PDF 분석 요청에 실패했습니다."));
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "COLLECTOR_METRIC event=gemini_final_failure stage=extraction status=400",
                            "COLLECTOR_METRIC event=gemini_final_failure stage=extraction status=429",
                            "COLLECTOR_METRIC event=gemini_final_failure stage=normalization status=network");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
