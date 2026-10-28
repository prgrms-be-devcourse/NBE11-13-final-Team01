package com.zipick.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 의 수집 실행 설정이 실제로 코드와 맞물리는지 본다.
 *
 * <p>실행 시각 값(startup-delay / catch-up-interval / daily-cron / cron-zone)은
 * {@code @Scheduled} 애너테이션이 문자열로 읽어 가므로 컴파일러가 오타를 잡아 주지 못한다.
 * cron 표현식이 틀리면 <b>기동 시점에야</b> 터지고, Duration 표기가 틀리면 조용히 기본값으로 돈다.
 * 설정 파일만 고치는 변경에서 그걸 놓치지 않도록 여기서 파싱까지 확인한다.
 */
class InfraCollectionScheduleConfigTest {

    private static final String PREFIX = "zipick.infra";

    private final StandardEnvironment environment = loadApplicationYml();

    @Test
    @DisplayName("수집 실행 설정이 InfraProperties 로 바인딩된다")
    void bindsCollectionProperties() {
        InfraProperties properties = Binder.get(environment)
                .bind(PREFIX, InfraProperties.class)
                .orElseThrow(() -> new AssertionError("zipick.infra 설정이 없습니다."));

        assertThat(properties.cacheTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.collection().enabled()).isTrue();
        assertThat(properties.collection().batchSize()).isEqualTo(100);
        assertThat(properties.collection().apiFailureThreshold()).isEqualTo(3);
        // 코드 fallback 과 같은 값이지만, 키 이름(catch-up-window)이 실제로 바인딩되는지는 여기서만 드러난다.
        // 키를 잘못 쓰면 바인딩이 조용히 실패하고 fallback 으로 돌아가기 때문이다.
        assertThat(properties.collection().catchUpWindow()).isEqualTo(Duration.ofHours(3));
        assertThat(properties.categories()).hasSize(6);
    }

    @Test
    @DisplayName("@Scheduled 가 읽는 값이 모두 있고 파싱된다")
    void scheduleValuesAreParsable() {
        assertThat(Duration.parse(property("collection.startup-delay"))).isPositive();
        assertThat(Duration.parse(property("collection.catch-up-interval"))).isPositive();
        assertThat(CronExpression.isValidExpression(property("collection.daily-cron"))).isTrue();
        assertThat(ZoneId.of(property("collection.cron-zone"))).isNotNull();
    }

    @Test
    @DisplayName("하루에 한 번만 실행되는 cron 이다")
    void dailyCronFiresOnceADay() {
        CronExpression cron = CronExpression.parse(property("collection.daily-cron"));

        LocalDateTime first = cron.next(LocalDateTime.of(2026, 10, 28, 0, 0));
        LocalDateTime second = cron.next(first);

        assertThat(Duration.between(first, second)).isEqualTo(Duration.ofDays(1));
    }

    private String property(String suffix) {
        String value = environment.getProperty(PREFIX + "." + suffix);
        assertThat(value).as(PREFIX + "." + suffix).isNotBlank();
        return value;
    }

    private static StandardEnvironment loadApplicationYml() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            StandardEnvironment loaded = new StandardEnvironment();
            sources.forEach(source -> loaded.getPropertySources().addFirst(source));
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("application.yml 을 읽지 못했습니다.", exception);
        }
    }
}
