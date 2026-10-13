package com.zipick.external.myhome.client;

import com.zipick.external.myhome.config.MyHomeApiProperties;
import com.zipick.external.myhome.dto.MyHomeApiResponse;
import com.zipick.external.myhome.dto.MyHomeComplex;
import com.zipick.external.myhome.dto.MyHomeComplexQuery;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
public class MyHomeApiClient {

    private static final ParameterizedTypeReference<MyHomeApiResponse<MyHomeNotice>> NOTICE_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<MyHomeApiResponse<MyHomeComplex>> COMPLEX_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final MyHomeApiProperties properties;

    public MyHomeApiClient(RestClient myHomeRestClient, MyHomeApiProperties properties) {
        this.restClient = myHomeRestClient;
        this.properties = properties;
    }

    public MyHomeApiResponse<MyHomeNotice> getNotices(MyHomeNoticeQuery query) {
        UriComponentsBuilder uriBuilder = baseUri(properties.noticeBaseUrl(), "/rsdtRcritNtcList")
                .queryParam("pageNo", query.pageNo())
                .queryParam("numOfRows", query.numOfRows());

        addIfPresent(uriBuilder, "brtcCode", query.brtcCode());
        addIfPresent(uriBuilder, "signguCode", query.signguCode());
        addIfPresent(uriBuilder, "suplyTy", query.suplyTy());
        addIfPresent(uriBuilder, "houseTy", query.houseTy());
        addIfPresent(uriBuilder, "lfstsTyAt", query.lfstsTyAt());
        addIfPresent(uriBuilder, "bassMtRntchrgSe", query.bassMtRntchrgSe());
        addIfPresent(uriBuilder, "yearMtBegin", query.yearMtBegin());
        addIfPresent(uriBuilder, "yearMtEnd", query.yearMtEnd());

        return get(uriBuilder.build().encode().toUri(), NOTICE_RESPONSE_TYPE);
    }

    public MyHomeApiResponse<MyHomeComplex> getComplexes(MyHomeComplexQuery query) {
        URI uri = baseUri(properties.complexBaseUrl(), "/rentalHouseGwList")
                .queryParam("brtcCode", query.brtcCode())
                .queryParam("signguCode", query.signguCode())
                .queryParam("pageNo", query.pageNo())
                .queryParam("numOfRows", query.numOfRows())
                .build()
                .encode()
                .toUri();

        return get(uri, COMPLEX_RESPONSE_TYPE);
    }

    private UriComponentsBuilder baseUri(String baseUrl, String path) {
        if (!StringUtils.hasText(properties.serviceKey())) {
            throw new IllegalStateException("마이홈 API 인증키가 설정되지 않았습니다.");
        }

        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(path)
                .queryParam("serviceKey", properties.serviceKey());
    }

    private <T> MyHomeApiResponse<T> get(
            URI uri,
            ParameterizedTypeReference<MyHomeApiResponse<T>> responseType
    ) {
        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(responseType);
    }

    private void addIfPresent(UriComponentsBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.queryParam(name, value);
        }
    }
}
