package com.zipick.external.myhome.client;

import com.zipick.external.myhome.config.MyHomeApiProperties;
import com.zipick.external.myhome.dto.MyHomeComplexQuery;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MyHomeApiClientTest {

    @Test
    void requestsNoticesWithOnlyPresentFilters() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MyHomeApiClient client = new MyHomeApiClient(builder.build(), properties("secret"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/rsdtRcritNtcList")))
                .andExpect(queryParam("serviceKey", "secret"))
                .andExpect(queryParam("pageNo", "2"))
                .andExpect(queryParam("brtcCode", "11"))
                .andExpect(queryParam("suplyTy", "%EA%B5%AD%EB%AF%BC%EC%9E%84%EB%8C%80"))
                .andRespond(withSuccess(successJson(), MediaType.APPLICATION_JSON));

        var response = client.getNotices(new MyHomeNoticeQuery(
                "11", null, 2, 100, "국민임대", null, null, null, null, null
        ));

        assertThat(response.response().header().resultCode()).isEqualTo("00");
        server.verify();
    }

    @Test
    void requestsComplexesAndRequiresServiceKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MyHomeApiClient client = new MyHomeApiClient(builder.build(), properties("secret"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/rentalHouseGwList")))
                .andExpect(queryParam("brtcCode", "11"))
                .andExpect(queryParam("signguCode", "11680"))
                .andRespond(withSuccess(successJson(), MediaType.APPLICATION_JSON));

        assertThat(client.getComplexes(new MyHomeComplexQuery("11", "11680", 1, 10))).isNotNull();
        server.verify();

        MyHomeApiClient missingKey = new MyHomeApiClient(RestClient.create(), properties(" "));
        assertThatThrownBy(() -> missingKey.getNotices(new MyHomeNoticeQuery(
                null, null, 1, 10, null, null, null, null, null, null
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("인증키");
    }

    private MyHomeApiProperties properties(String key) {
        return new MyHomeApiProperties(key, "https://example.test/notice", "https://example.test/complex");
    }

    private String successJson() {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},"
                + "\"body\":{\"totalCount\":\"0\",\"numOfRows\":\"10\",\"pageNo\":\"1\",\"item\":[]}}}";
    }
}
