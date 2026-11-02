package com.zipick.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCollectorObservationTest {
    @TempDir Path dir;

    @Test void directRunRecordsFinalFailuresWithoutCountingRetriesAsFinal() throws Exception {
        try (LocalCollectorObservation observation = new LocalCollectorObservation("ai", dir)) {
            assertThat(Files.readString(dir.resolve("zipick_collector_ai_running.prom"))).contains("} 1");
            observation.record("COLLECTOR_METRIC event=gemini_http_error stage=extraction status=503 model=x");
            observation.record("COLLECTOR_METRIC event=gemini_http_success stage=extraction model=x");
            observation.record("COLLECTOR_METRIC event=gemini_final_failure stage=normalization status=503");
            observation.record("COLLECTOR_METRIC event=ai_document_error stage=extraction reason=incomplete documentKey=x");
            observation.success();
        }
        String metrics = Files.readString(dir.resolve("zipick_collector_ai.prom"));
        assertThat(metrics).contains("last_gemini_final_failures{job_type=\"ai\",stage=\"normalization\",category=\"5xx\"} 1")
                .contains("last_gemini_final_failures{job_type=\"ai\",stage=\"extraction\",category=\"5xx\"} 0")
                .contains("last_ai_document_errors{job_type=\"ai\",reason=\"incomplete\"} 1")
                .contains("last_run_success{job_type=\"ai\"} 1");
        assertThat(Files.readString(dir.resolve("zipick_collector_ai_running.prom"))).contains("} 0");
    }

    @Test void unsuccessfulRunPreservesLastSuccess() throws Exception {
        try (LocalCollectorObservation first = new LocalCollectorObservation("basic", dir)) { first.success(); }
        String before = Files.readString(dir.resolve("zipick_collector_basic.prom"));
        try (LocalCollectorObservation second = new LocalCollectorObservation("basic", dir)) {
            second.record("COLLECTOR_METRIC event=detail_error noticeId=42 source=SH");
            second.record("COLLECTOR_METRIC event=notice_error noticeId=42 source=SH");
        }
        String after = Files.readString(dir.resolve("zipick_collector_basic.prom"));
        String previousSuccess = before.lines().filter(s -> s.contains("last_success_timestamp_seconds")).findFirst().orElseThrow();
        assertThat(after).contains(previousSuccess).contains("last_notice_errors{job_type=\"basic\"} 1")
                .contains("last_run_success{job_type=\"basic\"} 0");
    }
}
