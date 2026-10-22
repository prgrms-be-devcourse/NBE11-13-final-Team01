package com.zipick.notice.controller;

import com.zipick.housing.dto.HouseFilterParams;
import com.zipick.notice.dto.MapHousingDetailResponse;
import com.zipick.notice.dto.MapHousingListResponse;
import com.zipick.notice.dto.NoticePageResponse;
import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MapHousingAsset;
import com.zipick.notice.service.MapHousingAssetService;
import com.zipick.notice.service.MapHousingQueryService;
import com.zipick.notice.service.MyHomeSyncService;
import com.zipick.notice.service.NoticeQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoticeControllersTest {

    @Test
    void delegatesNoticeAndSyncRequests() {
        NoticeQueryService query = mock(NoticeQueryService.class);
        NoticePageResponse page = new NoticePageResponse(1, 10, 0, 0, List.of());
        when(query.getNotices(null, null, 1, 10, null, null, null, null, null, null)).thenReturn(page);
        assertThat(new NoticeController(query).getNotices(
                null, null, 1, 10, null, null, null, null, null, null
        )).isSameAs(page);

        MyHomeSyncService sync = mock(MyHomeSyncService.class);
        NoticeSyncResponse response = new NoticeSyncResponse(
                1, 1, 0, 0, 0, 0, 0, 0, Instant.now(), Instant.now()
        );
        when(sync.synchronize()).thenReturn(response);
        assertThat(new NoticeSyncController(sync).synchronize()).isSameAs(response);
    }

    @Test
    void delegatesMapQueriesAndBuildsInlineAssetResponses() {
        MapHousingQueryService query = mock(MapHousingQueryService.class);
        MapHousingAssetService assets = mock(MapHousingAssetService.class);
        MapHousingController controller = new MapHousingController(query, assets);
        MapHousingListResponse list = new MapHousingListResponse(0, List.of());
        MapHousingDetailResponse detail = mock(MapHousingDetailResponse.class);
        when(query.findHousings(any())).thenReturn(list);
        when(query.findDetail(1L)).thenReturn(detail);
        when(assets.getAttachment(1L, 2L)).thenReturn(
                new MapHousingAsset(new byte[]{1}, MediaType.IMAGE_PNG, "도면.png")
        );
        when(assets.getMedia(1L, 3L)).thenReturn(
                new MapHousingAsset(new byte[]{2}, MediaType.IMAGE_JPEG, "사진.jpg")
        );

        assertThat(controller.list(new HouseFilterParams()).data()).isSameAs(list);
        assertThat(controller.detail(1L).data()).isSameAs(detail);
        assertThat(controller.attachmentContent(1L, 2L).getHeaders().getContentType())
                .isEqualTo(MediaType.IMAGE_PNG);
        assertThat(controller.mediaContent(1L, 3L).getBody()).containsExactly(2);
    }
}
