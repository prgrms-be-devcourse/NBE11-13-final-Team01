package com.zipick.external.myhome.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.external.myhome.client.MyHomeApiClient;
import com.zipick.external.myhome.dto.MyHomeApiResponse;
import com.zipick.external.myhome.dto.MyHomeComplex;
import com.zipick.external.myhome.dto.MyHomeComplexQuery;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import org.junit.jupiter.api.Test;

class MyHomeApiControllerTest {

    private final MyHomeApiClient client = mock(MyHomeApiClient.class);
    private final MyHomeApiController controller = new MyHomeApiController(client);

    @Test
    void getNoticesBuildsQueryAndReturnsClientResponse() {
        MyHomeApiResponse<MyHomeNotice> expected = mock(MyHomeApiResponse.class);
        MyHomeNoticeQuery query = new MyHomeNoticeQuery(
                "11", "11680", 2, 20, "01", "02", "Y", "A", "202601", "202612");
        when(client.getNotices(query)).thenReturn(expected);

        MyHomeApiResponse<MyHomeNotice> result = controller.getNotices(
                "11", "11680", 2, 20, "01", "02", "Y", "A", "202601", "202612");

        assertThat(result).isSameAs(expected);
        verify(client).getNotices(query);
    }

    @Test
    void getComplexesBuildsQueryAndReturnsClientResponse() {
        MyHomeApiResponse<MyHomeComplex> expected = mock(MyHomeApiResponse.class);
        MyHomeComplexQuery query = new MyHomeComplexQuery("11", "11680", 1, 10);
        when(client.getComplexes(query)).thenReturn(expected);

        MyHomeApiResponse<MyHomeComplex> result = controller.getComplexes("11", "11680", 1, 10);

        assertThat(result).isSameAs(expected);
        verify(client).getComplexes(query);
    }
}
