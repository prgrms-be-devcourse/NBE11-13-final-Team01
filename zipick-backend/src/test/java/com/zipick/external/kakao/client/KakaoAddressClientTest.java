package com.zipick.external.kakao.client;

import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoAddressClientTest {

    @Test
    void rejectsCallsWithoutConfiguredKey() {
        KakaoAddressClient client = new KakaoAddressClient(
                RestClient.create(), new KakaoLocalApiProperties("YOUR_KEY", null)
        );

        assertThatThrownBy(() -> client.search("서울"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.searchKeyword("서울아파트"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void convertsEmptyAddressAndKeywordResponsesToEmptyDocuments() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoAddressClient client = new KakaoAddressClient(
                builder.build(), new KakaoLocalApiProperties("key", null)
        );
        server.expect(method(HttpMethod.GET)).andRespond(withNoContent());
        server.expect(method(HttpMethod.GET)).andRespond(withNoContent());

        assertThat(client.search("서울").documents()).isEmpty();
        assertThat(client.searchKeyword("서울아파트").documents()).isEmpty();
        server.verify();
    }

    @Test
    void returnsMappedAddressAndKeywordResponses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoAddressClient client = new KakaoAddressClient(
                builder.build(), new KakaoLocalApiProperties(" key ", null)
        );
        server.expect(method(HttpMethod.GET))
                .andExpect(queryParam("analyze_type", "exact"))
                .andExpect(header("Authorization", "KakaoAK key"))
                .andRespond(withSuccess("{\"documents\":[{\"x\":\"127\",\"y\":\"37\"}]}", MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET))
                .andExpect(queryParam("size", "15"))
                .andRespond(withSuccess("{\"documents\":[{\"place_name\":\"서울아파트\",\"x\":\"127\",\"y\":\"37\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.search("서울").documents()).hasSize(1);
        assertThat(client.searchKeyword("서울아파트").documents()).hasSize(1);
        server.verify();
    }
}
