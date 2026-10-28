package com.zipick.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.ResolvableType;

class MonitoringConfigurationTest {
    @Test
    void apiResponseBucketsAreBoundAsDurations() throws Exception {
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        ResolvableType mapType = ResolvableType.forClassWithGenerics(Map.class,
                ResolvableType.forClass(String.class),
                ResolvableType.forClassWithGenerics(List.class, Duration.class));
        Map<String, List<Duration>> slo = new Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bind("management.metrics.distribution.slo", Bindable.<Map<String, List<Duration>>>of(mapType))
                .orElseThrow(() -> new IllegalStateException("API 응답 시간 구간이 설정되지 않았습니다."));
        assertThat(slo.get("http.server.requests"))
                .containsExactly(Duration.ofMillis(100), Duration.ofMillis(300),
                        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(10));
    }
}
