package com.zipick.external;

import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 외부 호출의 결과와 시간을 기록하며 URL·인증정보는 태그에 넣지 않는다. */
public final class ExternalApiMetrics {
    private ExternalApiMetrics() { }

    public static <T> T measure(String provider, String stage, Supplier<T> request) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return request.get();
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            Metrics.counter("zipick.external.api.calls", "provider", provider, "stage", stage, "outcome", outcome).increment();
            Metrics.timer("zipick.external.api.duration", "provider", provider, "stage", stage, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
