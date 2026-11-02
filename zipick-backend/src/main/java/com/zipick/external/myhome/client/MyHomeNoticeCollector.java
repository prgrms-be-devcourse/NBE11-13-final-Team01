package com.zipick.external.myhome.client;

import com.zipick.external.myhome.dto.MyHomeApiResponse;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MyHomeNoticeCollector {

    private static final int EXTERNAL_PAGE_SIZE = 100;
    private static final int MAX_EXTERNAL_PAGES = 100;

    private final MyHomeApiClient myHomeApiClient;

    public MyHomeNoticeCollector(MyHomeApiClient myHomeApiClient) {
        this.myHomeApiClient = myHomeApiClient;
    }

    public List<MyHomeNotice> collectAll(MyHomeNoticeQuery filter) {
        List<MyHomeNotice> rows = new ArrayList<>();
        Integer expectedTotalCount = null;

        for (int pageNo = 1; pageNo <= MAX_EXTERNAL_PAGES; pageNo++) {
            MyHomeApiResponse<MyHomeNotice> response = myHomeApiClient.getNotices(withPage(filter, pageNo));
            MyHomeApiResponse.Body<MyHomeNotice> body = requireSuccessfulBody(response);
            int currentTotalCount = parseTotalCount(body.totalCount());

            if (expectedTotalCount == null) {
                expectedTotalCount = currentTotalCount;
            } else if (expectedTotalCount != currentTotalCount) {
                throw new IllegalStateException("마이홈 API 수집 중 전체 건수가 변경되었습니다. 다시 수집해야 합니다.");
            }

            rows.addAll(body.item());
            if (rows.size() >= expectedTotalCount) {
                return List.copyOf(rows);
            }
            if (body.item().isEmpty()) {
                throw new IllegalStateException("마이홈 API가 전체 건수보다 적은 데이터를 반환했습니다.");
            }
        }

        throw new IllegalStateException("마이홈 API 최대 페이지 수를 초과했습니다.");
    }

    private MyHomeNoticeQuery withPage(MyHomeNoticeQuery filter, int pageNo) {
        return new MyHomeNoticeQuery(
                filter.brtcCode(),
                filter.signguCode(),
                pageNo,
                EXTERNAL_PAGE_SIZE,
                filter.suplyTy(),
                filter.houseTy(),
                filter.lfstsTyAt(),
                filter.bassMtRntchrgSe(),
                filter.yearMtBegin(),
                filter.yearMtEnd()
        );
    }

    private MyHomeApiResponse.Body<MyHomeNotice> requireSuccessfulBody(MyHomeApiResponse<MyHomeNotice> response) {
        if (response == null || response.response() == null || response.response().header() == null) {
            throw new IllegalStateException("마이홈 API 응답 형식이 올바르지 않습니다.");
        }
        if (!"00".equals(response.response().header().resultCode())) {
            throw new IllegalStateException("마이홈 API 조회에 실패했습니다: "
                    + response.response().header().resultMsg());
        }
        if (response.response().body() == null) {
            throw new IllegalStateException("마이홈 API 응답 본문이 없습니다.");
        }
        return response.response().body();
    }

    private int parseTotalCount(String totalCount) {
        try {
            return Integer.parseInt(totalCount);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("마이홈 API 전체 건수를 해석할 수 없습니다: " + totalCount, exception);
        }
    }
}
