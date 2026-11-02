package com.zipick.external;

import com.zipick.external.gh.GhCollectorProperties;
import com.zipick.external.hug.HugCollectorProperties;
import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.external.sh.ShCollectorProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorPropertiesBranchTest {

    @Test
    void ghAppliesDefaultsAndKeepsExplicitValues() {
        GhCollectorProperties defaults = new GhCollectorProperties(null, null, null);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.maxPages()).isEqualTo(5);
        assertThat(defaults.requestDelayMillis()).isEqualTo(100L);

        GhCollectorProperties explicit = new GhCollectorProperties(false, 3, 0L);
        assertThat(explicit.isEnabled()).isFalse();
        assertThat(explicit.maxPages()).isEqualTo(3);
        assertThat(explicit.requestDelayMillis()).isZero();

        assertThat(new GhCollectorProperties(true, 0, -1L).maxPages()).isEqualTo(5);
    }

    @Test
    void shAppliesDefaultsAndTrimsExplicitUrl() {
        ShCollectorProperties defaults = new ShCollectorProperties(null, " ", 0, 0, -1L);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.listUrl()).contains("i-sh.co.kr");
        assertThat(defaults.maxPages()).isEqualTo(40);
        assertThat(defaults.lookbackDays()).isEqualTo(365);
        assertThat(defaults.requestDelayMillis()).isEqualTo(100L);

        ShCollectorProperties explicit = new ShCollectorProperties(false, " https://example.com/list ", 2, 30, 0L);
        assertThat(explicit.isEnabled()).isFalse();
        assertThat(explicit.listUrl()).isEqualTo("https://example.com/list");
        assertThat(explicit.maxPages()).isEqualTo(2);
        assertThat(explicit.lookbackDays()).isEqualTo(30);
    }

    @Test
    void hugAppliesDefaultsAndKeepsExplicitValues() {
        HugCollectorProperties defaults = new HugCollectorProperties(null, null, -1, -1L);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.listUrl()).contains("khug.or.kr");
        assertThat(defaults.maxPages()).isEqualTo(20);
        assertThat(defaults.requestDelayMillis()).isEqualTo(100L);

        HugCollectorProperties explicit = new HugCollectorProperties(false, " https://example.com/list ", 2, 0L);
        assertThat(explicit.isEnabled()).isFalse();
        assertThat(explicit.listUrl()).isEqualTo("https://example.com/list");
        assertThat(explicit.maxPages()).isEqualTo(2);
        assertThat(explicit.requestDelayMillis()).isZero();
    }

    @Test
    void kakaoConfigurationRejectsBlankAndPlaceholderKeys() {
        assertThat(new KakaoLocalApiProperties(null, null).isConfigured()).isFalse();
        assertThat(new KakaoLocalApiProperties(" ", " ").isConfigured()).isFalse();
        assertThat(new KakaoLocalApiProperties("YOUR_KEY", null).isConfigured()).isFalse();

        KakaoLocalApiProperties configured = new KakaoLocalApiProperties(" key ", " https://example.com ");
        assertThat(configured.isConfigured()).isTrue();
        assertThat(configured.baseUrl()).isEqualTo("https://example.com");
    }
}
