package com.zipick.external.kakao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KakaoAddressSearchResponse(List<Document> documents) {

    public KakaoAddressSearchResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public record Document(
            String x,
            String y,
            Address address,
            @JsonProperty("road_address") RoadAddress roadAddress
    ) {
    }

    public record Address(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("region_1depth_name") String provinceName,
            @JsonProperty("region_2depth_name") String districtName,
            @JsonProperty("region_3depth_name") String legalDongName
    ) {
    }

    public record RoadAddress(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("region_1depth_name") String provinceName,
            @JsonProperty("region_2depth_name") String districtName,
            @JsonProperty("region_3depth_name") String legalDongName,
            @JsonProperty("road_name") String roadName,
            @JsonProperty("building_name") String buildingName
    ) {
    }
}
