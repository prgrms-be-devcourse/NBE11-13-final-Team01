package com.zipick.external.ai;

import com.zipick.external.myhome.config.YamlPropertySourceFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@EnableConfigurationProperties({GeminiPdfParserProperties.class, GeminiNormalizationProperties.class})
@PropertySource(
        value = {
                "file:./config/application-secret.yml",
                "file:./zipick-backend/config/application-secret.yml"
        },
        ignoreResourceNotFound = true,
        factory = YamlPropertySourceFactory.class
)
public class GeminiPdfParserConfig {
}
