package com.zipick.external.myhome.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyHomeApiConfigurationTest {

    @Test
    void appliesDefaultAndTrimmedBaseUrls() {
        MyHomeApiProperties defaults = new MyHomeApiProperties("key", null, " ");
        MyHomeApiProperties custom = new MyHomeApiProperties(
                "key", " https://notice.example ", " https://complex.example "
        );

        assertThat(defaults.noticeBaseUrl()).contains("HWSPR02");
        assertThat(defaults.complexBaseUrl()).contains("HWSPR04");
        assertThat(custom.noticeBaseUrl()).isEqualTo("https://notice.example");
        assertThat(custom.complexBaseUrl()).isEqualTo("https://complex.example");
        assertThat(new MyHomeApiConfig().myHomeRestClient()).isNotNull();
    }
}
