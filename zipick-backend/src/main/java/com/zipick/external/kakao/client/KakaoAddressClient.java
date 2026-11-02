package com.zipick.external.kakao.client;

import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.external.kakao.dto.KakaoAddressSearchResponse;
import com.zipick.external.kakao.dto.KakaoKeywordSearchResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoAddressClient {

    private final RestClient restClient;
    private final KakaoLocalApiProperties properties;

    public KakaoAddressClient(
            @Qualifier("kakaoLocalRestClient") RestClient restClient,
            KakaoLocalApiProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public KakaoAddressSearchResponse search(String query) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("카카오 REST API 키가 설정되지 않았습니다.");
        }

        KakaoAddressSearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/address.json")
                        .queryParam("query", query)
                        .queryParam("analyze_type", "exact")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey().trim())
                .retrieve()
                .body(KakaoAddressSearchResponse.class);

        return response == null ? new KakaoAddressSearchResponse(null) : response;
    }

    public KakaoKeywordSearchResponse searchKeyword(String query) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("카카오 REST API 키가 설정되지 않았습니다.");
        }

        KakaoKeywordSearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .queryParam("size", 15)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey().trim())
                .retrieve()
                .body(KakaoKeywordSearchResponse.class);

        return response == null ? new KakaoKeywordSearchResponse(null) : response;
    }
}
