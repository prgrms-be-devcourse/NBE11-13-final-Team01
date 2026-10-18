package com.zipick.external.kakao.client;

import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.external.kakao.dto.KakaoCategorySearchResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * 주택 좌표 주변의 생활시설을 카테고리별로 검색한다.
 * 주소 검색(KakaoAddressClient)과 같은 REST 키·RestClient 빈을 그대로 쓴다.
 */
@Component
public class KakaoCategorySearchClient {

    private final RestClient restClient;
    private final KakaoLocalApiProperties properties;

    public KakaoCategorySearchClient(
            @Qualifier("kakaoLocalRestClient") RestClient restClient,
            KakaoLocalApiProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * @param kakaoCategoryCode 카카오 카테고리 그룹 코드 (SW8, MT1 ...)
     * @param radiusMeter       검색 반경(m). 카카오 허용 범위는 0~20000 이다.
     */
    public KakaoCategorySearchResponse search(
            String kakaoCategoryCode,
            BigDecimal latitude,
            BigDecimal longitude,
            int radiusMeter
    ) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("카카오 REST API 키가 설정되지 않았습니다.");
        }

        KakaoCategorySearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/category.json")
                        .queryParam("category_group_code", kakaoCategoryCode)
                        // x = 경도, y = 위도. 둘을 넘겨야 documents[].distance 가 내려온다.
                        .queryParam("x", longitude.toPlainString())
                        .queryParam("y", latitude.toPlainString())
                        .queryParam("radius", radiusMeter)
                        .queryParam("sort", "distance")
                        // 화면에는 최근접 1곳만 쓰고, 전체 개수는 meta.total_count 로 받는다.
                        .queryParam("size", 1)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey().trim())
                .retrieve()
                .body(KakaoCategorySearchResponse.class);

        if (response == null) {
            throw new IllegalStateException("카카오 카테고리 검색 응답이 비어 있습니다.");
        }
        return response;
    }
}
