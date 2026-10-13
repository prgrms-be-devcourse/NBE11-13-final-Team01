package com.zipick.external.myhome.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MyHomeApiResponseMappingTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void mapsNoticeResponse() throws Exception {
        String json = """
                {
                  "response": {
                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
                    "body": {
                      "totalCount": "1",
                      "numOfRows": "10",
                      "pageNo": "1",
                      "item": [{
                        "pblancId": "123",
                        "pblancNm": "행복주택 모집공고",
                        "suplyInsttNm": "LH",
                        "rentGtn": 10000000,
                        "mtRntchrg": 150000
                      }]
                    }
                  }
                }
                """;

        MyHomeApiResponse<MyHomeNotice> response = jsonMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        MyHomeNotice notice = response.response().body().item().getFirst();
        assertThat(response.response().header().resultCode()).isEqualTo("00");
        assertThat(notice.pblancId()).isEqualTo("123");
        assertThat(notice.suplyInsttNm()).isEqualTo("LH");
        assertThat(notice.rentGtn()).isEqualTo(10_000_000L);
    }

    @Test
    void mapsComplexResponse() throws Exception {
        String json = """
                {
                  "response": {
                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
                    "body": {
                      "totalCount": "1",
                      "numOfRows": "10",
                      "pageNo": "1",
                      "item": [{
                        "hsmpSn": 77,
                        "insttNm": "LH전북",
                        "hsmpNm": "테스트 단지",
                        "rnAdres": "전북특별자치도 익산시",
                        "suplyPrvuseAr": 36.5,
                        "bassRentGtn": 12000000
                      }]
                    }
                  }
                }
                """;

        MyHomeApiResponse<MyHomeComplex> response = jsonMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        MyHomeComplex complex = response.response().body().item().getFirst();
        assertThat(complex.hsmpSn()).isEqualTo(77L);
        assertThat(complex.insttNm()).isEqualTo("LH전북");
        assertThat(complex.suplyPrvuseAr()).isEqualByComparingTo("36.5");
    }
}
