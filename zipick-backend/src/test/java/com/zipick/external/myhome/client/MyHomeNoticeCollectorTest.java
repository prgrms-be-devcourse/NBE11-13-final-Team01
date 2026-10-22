package com.zipick.external.myhome.client;

import com.zipick.external.myhome.dto.MyHomeApiResponse;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyHomeNoticeCollectorTest {

    private final MyHomeApiClient client = mock(MyHomeApiClient.class);
    private final MyHomeNoticeCollector collector = new MyHomeNoticeCollector(client);

    @Test
    void collectsEveryPageUsingExternalPageSize() {
        MyHomeNotice first = row("n1");
        MyHomeNotice second = row("n2");
        when(client.getNotices(any()))
                .thenReturn(response("2", List.of(first)))
                .thenReturn(response("2", List.of(second)));

        List<MyHomeNotice> result = collector.collectAll(filter());

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void rejectsMalformedAndFailedResponses() {
        when(client.getNotices(any())).thenReturn(null);
        assertThatThrownBy(() -> collector.collectAll(filter()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("응답 형식");

        when(client.getNotices(any())).thenReturn(new MyHomeApiResponse<>(
                new MyHomeApiResponse.ApiResponse<>(new MyHomeApiResponse.Header("99", "실패"), null)
        ));
        assertThatThrownBy(() -> collector.collectAll(filter()))
                .hasMessageContaining("실패");

        when(client.getNotices(any())).thenReturn(new MyHomeApiResponse<>(
                new MyHomeApiResponse.ApiResponse<>(new MyHomeApiResponse.Header("00", "정상"), null)
        ));
        assertThatThrownBy(() -> collector.collectAll(filter()))
                .hasMessageContaining("본문");
    }

    @Test
    void rejectsInvalidOrChangingTotalCountAndPrematureEmptyPage() {
        when(client.getNotices(any())).thenReturn(response("invalid", List.of()));
        assertThatThrownBy(() -> collector.collectAll(filter()))
                .hasMessageContaining("전체 건수를 해석");

        when(client.getNotices(any()))
                .thenReturn(response("3", List.of(row("n1"))))
                .thenReturn(response("4", List.of(row("n2"))));
        assertThatThrownBy(() -> collector.collectAll(filter()))
                .hasMessageContaining("전체 건수가 변경");

        when(client.getNotices(any())).thenReturn(response("1", List.of()));
        assertThatThrownBy(() -> collector.collectAll(filter()))
                .hasMessageContaining("적은 데이터를 반환");
    }

    private MyHomeApiResponse<MyHomeNotice> response(String total, List<MyHomeNotice> rows) {
        return new MyHomeApiResponse<>(new MyHomeApiResponse.ApiResponse<>(
                new MyHomeApiResponse.Header("00", "정상"),
                new MyHomeApiResponse.Body<>(total, "100", "1", rows)
        ));
    }

    private MyHomeNoticeQuery filter() {
        return new MyHomeNoticeQuery("11", "11680", 9, 5,
                "국민임대", "아파트", "Y", "1", "202601", "202612");
    }

    private MyHomeNotice row(String id) {
        return new MyHomeNotice(null, id, null, "공고중", "공고", "LH", null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
