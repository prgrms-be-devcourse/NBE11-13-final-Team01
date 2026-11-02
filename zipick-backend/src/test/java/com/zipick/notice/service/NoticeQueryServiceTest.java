package com.zipick.notice.service;

import com.zipick.external.myhome.client.MyHomeNoticeCollector;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoticeQueryServiceTest {

    @Test
    void paginatesAggregatedNoticesAndHandlesEmptyResult() {
        MyHomeNoticeCollector collector = mock(MyHomeNoticeCollector.class);
        MyHomeNoticeAggregator aggregator = mock(MyHomeNoticeAggregator.class);
        NoticeQueryService service = new NoticeQueryService(collector, aggregator);
        when(collector.collectAll(any())).thenReturn(List.of());
        when(aggregator.aggregate(any())).thenReturn(List.of(notice("1"), notice("2"), notice("3")));

        var page = service.getNotices("11", "11680", 2, 2,
                "국민임대", "아파트", "Y", "1", "202601", "202612");

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(NoticeResponse::noticeId).containsExactly("3");

        when(aggregator.aggregate(any())).thenReturn(List.of());
        assertThat(service.getNotices(null, null, 5, 10,
                null, null, null, null, null, null).totalPages()).isZero();
    }

    private NoticeResponse notice(String id) {
        return new NoticeResponse("LH", id, "공고중", "공고", "LH", null,
                null, null, null, null, null, null, null, null, List.of(), List.of());
    }
}
