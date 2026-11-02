package com.zipick.external.kakao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 카카오 로컬 "카테고리로 장소 검색"(/v2/local/search/category.json) 응답.
 *
 * meta.totalCount 는 size 와 무관하게 반경 내 전체 검색 결과 수를 알려준다.
 * 그래서 size=1 한 번으로 "최근접 시설 1곳 + 전체 개수"를 동시에 얻는다.
 * (pageable_count 는 45로 잘리므로 개수 표시에는 쓰지 않는다.)
 */
public record KakaoCategorySearchResponse(Meta meta, List<Document> documents) {

    public KakaoCategorySearchResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public record Meta(
            @JsonProperty("total_count") Integer totalCount,
            @JsonProperty("pageable_count") Integer pageableCount,
            @JsonProperty("is_end") Boolean isEnd
    ) {
    }

    public record Document(
            String id,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            String x,
            String y,
            /** 중심 좌표로부터의 직선거리(m). 요청에 x·y 를 넘겼을 때만 내려온다. */
            String distance,
            @JsonProperty("place_url") String placeUrl,
            String phone
    ) {
    }
}
