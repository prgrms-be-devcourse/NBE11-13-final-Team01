package com.zipick.external.myhome.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MyHomeApiProperties.class)
@PropertySource(
        value = {
                "file:./config/application-secret.yml",
                "file:./zipick-backend/config/application-secret.yml"
        },
        ignoreResourceNotFound = true,
        factory = YamlPropertySourceFactory.class
)
public class MyHomeApiConfig {

    @Bean
    RestClient myHomeRestClient() {
        return RestClient.builder()
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
