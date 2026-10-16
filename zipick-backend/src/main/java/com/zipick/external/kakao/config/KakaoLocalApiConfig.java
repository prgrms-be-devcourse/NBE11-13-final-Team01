package com.zipick.external.kakao.config;

import com.zipick.external.myhome.config.YamlPropertySourceFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KakaoLocalApiProperties.class)
@PropertySource(
        value = {
                "file:./config/application-secret.yml",
                "file:./zipick-backend/config/application-secret.yml"
        },
        ignoreResourceNotFound = true,
        factory = YamlPropertySourceFactory.class
)
public class KakaoLocalApiConfig {

    @Bean
    RestClient kakaoLocalRestClient(KakaoLocalApiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }
}
