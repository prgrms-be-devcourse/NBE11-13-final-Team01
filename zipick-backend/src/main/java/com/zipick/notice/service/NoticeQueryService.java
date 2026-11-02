package com.zipick.notice.service;

import com.zipick.external.myhome.client.MyHomeNoticeCollector;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import com.zipick.notice.dto.NoticePageResponse;
import com.zipick.notice.dto.NoticeResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NoticeQueryService {

    private final MyHomeNoticeCollector collector;
    private final MyHomeNoticeAggregator aggregator;

    public NoticeQueryService(MyHomeNoticeCollector collector, MyHomeNoticeAggregator aggregator) {
        this.collector = collector;
        this.aggregator = aggregator;
    }

    public NoticePageResponse getNotices(
            String brtcCode,
            String signguCode,
            int pageNo,
            int numOfRows,
            String suplyTy,
            String houseTy,
            String lfstsTyAt,
            String bassMtRntchrgSe,
            String yearMtBegin,
            String yearMtEnd
    ) {
        List<MyHomeNotice> rawRows = collector.collectAll(new MyHomeNoticeQuery(
                brtcCode, signguCode, 1, 100, suplyTy, houseTy,
                lfstsTyAt, bassMtRntchrgSe, yearMtBegin, yearMtEnd
        ));
        List<NoticeResponse> notices = aggregator.aggregate(rawRows);

        int totalCount = notices.size();
        int totalPages = totalCount == 0 ? 0 : (totalCount + numOfRows - 1) / numOfRows;
        int fromIndex = Math.min((pageNo - 1) * numOfRows, totalCount);
        int toIndex = Math.min(fromIndex + numOfRows, totalCount);

        return new NoticePageResponse(
                pageNo,
                numOfRows,
                totalCount,
                totalPages,
                notices.subList(fromIndex, toIndex)
        );
    }

}
