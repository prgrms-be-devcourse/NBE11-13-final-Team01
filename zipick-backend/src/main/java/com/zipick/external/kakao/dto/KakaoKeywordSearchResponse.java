package com.zipick.external.kakao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KakaoKeywordSearchResponse(List<Document> documents) {

    public KakaoKeywordSearchResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public record Document(
            @JsonProperty("place_name") String placeName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            String x,
            String y
    ) {
    }
}
