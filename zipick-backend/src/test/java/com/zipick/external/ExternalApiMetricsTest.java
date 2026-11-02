package com.zipick.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ExternalApiMetricsTest {
    @Test
    void recordsSuccessAndFailureWithoutExposingRequestData() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            assertThat(ExternalApiMetrics.measure("myhome", "api", () -> "ok")).isEqualTo("ok");
            assertThatThrownBy(() -> ExternalApiMetrics.measure("myhome", "api", () -> {
                throw new IllegalStateException("unavailable");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(registry.get("zipick.external.api.calls").tags("provider", "myhome", "stage", "api", "outcome", "success").counter().count()).isEqualTo(1);
            assertThat(registry.get("zipick.external.api.calls").tags("provider", "myhome", "stage", "api", "outcome", "error").counter().count()).isEqualTo(1);
            assertThat(registry.get("zipick.external.api.duration").tags("provider", "myhome", "stage", "api", "outcome", "error").timer().count()).isEqualTo(1);
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }
}
