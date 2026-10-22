package com.zipick.external.myhome.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;

class YamlPropertySourceFactoryTest {

    private final YamlPropertySourceFactory factory = new YamlPropertySourceFactory();

    @Test
    void loadsYamlUsingExplicitSourceName() throws Exception {
        EncodedResource resource = yaml("zipick:\n  sources:\n    sh:\n      enabled: true\n");

        PropertySource<?> source = factory.createPropertySource("myhome-test", resource);

        assertThat(source.getName()).isEqualTo("myhome-test");
        assertThat(source.getProperty("zipick.sources.sh.enabled")).isEqualTo(true);
    }

    @Test
    void usesResourceDescriptionWhenNameIsMissing() throws Exception {
        EncodedResource resource = yaml("zipick:\n  value: sample\n");

        PropertySource<?> source = factory.createPropertySource(null, resource);

        assertThat(source.getName()).contains("Byte array resource");
        assertThat(source.getProperty("zipick.value")).isEqualTo("sample");
    }

    private EncodedResource yaml(String value) {
        return new EncodedResource(new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)));
    }
}
