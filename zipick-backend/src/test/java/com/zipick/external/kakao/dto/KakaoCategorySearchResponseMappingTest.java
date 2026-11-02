package com.zipick.external.kakao.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoCategorySearchResponseMappingTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("size=1 응답에서도 meta.total_count 로 반경 내 전체 시설 수를 받는다")
    void mapsMetaAndNearestDocument() {
        String json = """
                {
                  "meta": {"total_count": 6, "pageable_count": 6, "is_end": false},
                  "documents": [{
                    "id": "12345678",
                    "place_name": "신설동역 1호선",
                    "address_name": "서울 동대문구 신설동 104-1",
                    "road_address_name": "서울 동대문구 왕산로 1",
                    "x": "127.0250",
                    "y": "37.5756",
                    "distance": "683",
                    "place_url": "http://place.map.kakao.com/12345678",
                    "phone": "02-1234-5678"
                  }]
                }
                """;

        KakaoCategorySearchResponse response =
                jsonMapper.readValue(json, KakaoCategorySearchResponse.class);

        assertThat(response.meta().totalCount()).isEqualTo(6);
        assertThat(response.documents()).hasSize(1);
        KakaoCategorySearchResponse.Document document = response.documents().getFirst();
        assertThat(document.placeName()).isEqualTo("신설동역 1호선");
        assertThat(document.distance()).isEqualTo("683");
        assertThat(document.roadAddressName()).isEqualTo("서울 동대문구 왕산로 1");
        assertThat(document.placeUrl()).isEqualTo("http://place.map.kakao.com/12345678");
    }

    @Test
    @DisplayName("검색 결과가 0건이면 documents 는 빈 목록이 된다")
    void mapsEmptyResult() {
        String json = """
                {"meta": {"total_count": 0, "pageable_count": 0, "is_end": true}, "documents": []}
                """;

        KakaoCategorySearchResponse response =
                jsonMapper.readValue(json, KakaoCategorySearchResponse.class);

        assertThat(response.meta().totalCount()).isZero();
        assertThat(response.documents()).isEmpty();
    }
}
