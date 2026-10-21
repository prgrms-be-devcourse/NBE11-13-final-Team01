package com.zipick.notice.service;

import com.zipick.external.gh.GhNoticeCollector;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.external.hug.HugNoticeCollector;
import com.zipick.external.myhome.client.MyHomeNoticeCollector;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.sh.ShNoticeCollector;
import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyHomeSyncServiceTest {

    @Test
    void combinesAvailableSourcesSkipsExpiredRowsAndGeocodes() {
        MyHomeNoticeCollector collector = mock(MyHomeNoticeCollector.class);
        MyHomeNoticeAggregator aggregator = mock(MyHomeNoticeAggregator.class);
        HousingDetailEnricher enricher = mock(HousingDetailEnricher.class);
        ShNoticeCollector sh = mock(ShNoticeCollector.class);
        GhNoticeCollector gh = mock(GhNoticeCollector.class);
        HugNoticeCollector hug = mock(HugNoticeCollector.class);
        NoticeSyncPersistenceService persistence = mock(NoticeSyncPersistenceService.class);
        HousingGeocodingService geocoding = mock(HousingGeocodingService.class);
        MyHomeSyncService service = new MyHomeSyncService(
                collector, aggregator, enricher, sh, gh, hug, persistence, geocoding
        );
        MyHomeNotice row = row("myhome-1");
        NoticeResponse active = notice("myhome-1", "2099/12/31");
        NoticeResponse expired = notice("expired", "2020-01-01");
        NoticeSyncCommand shCommand = new NoticeSyncCommand(notice("sh-1", "2099-12-31"), "{}");
        when(collector.collectAll(any())).thenReturn(List.of(row, row(" ")));
        when(aggregator.aggregate(any())).thenReturn(List.of(active, expired));
        when(enricher.enrich(active)).thenReturn(active);
        when(sh.collect()).thenReturn(List.of(shCommand));
        when(gh.collect()).thenThrow(new IllegalStateException("GH unavailable"));
        when(hug.collect()).thenReturn(List.of());
        when(persistence.synchronize(any(), any(Instant.class), anySet()))
                .thenReturn(new NoticeSyncResult(2, 1, 3, 4, 0, 0, 0));

        var response = service.synchronize();

        assertThat(response.collectedRows()).isEqualTo(3);
        assertThat(response.createdNotices()).isEqualTo(2);
        ArgumentCaptor<List<NoticeSyncCommand>> commands = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Set<String>> completed = ArgumentCaptor.forClass(Set.class);
        verify(persistence).synchronize(commands.capture(), any(Instant.class), completed.capture());
        assertThat(commands.getValue()).extracting(command -> command.notice().noticeId())
                .containsExactly("myhome-1", "sh-1");
        assertThat(completed.getValue()).containsExactlyInAnyOrder(
                "LH", "BMC", "GBDC", "MYHOME", "SH", "HUG"
        );
        verify(geocoding).enrichMissingCoordinates();
    }

    @Test
    void rejectsConcurrentSynchronizationAndAlwaysReleasesLock() throws Exception {
        MyHomeNoticeCollector collector = mock(MyHomeNoticeCollector.class);
        MyHomeNoticeAggregator aggregator = mock(MyHomeNoticeAggregator.class);
        HousingDetailEnricher enricher = mock(HousingDetailEnricher.class);
        ShNoticeCollector sh = mock(ShNoticeCollector.class);
        GhNoticeCollector gh = mock(GhNoticeCollector.class);
        HugNoticeCollector hug = mock(HugNoticeCollector.class);
        NoticeSyncPersistenceService persistence = mock(NoticeSyncPersistenceService.class);
        HousingGeocodingService geocoding = mock(HousingGeocodingService.class);
        MyHomeSyncService service = new MyHomeSyncService(
                collector, aggregator, enricher, sh, gh, hug, persistence, geocoding
        );
        CountDownLatch enteredCollector = new CountDownLatch(1);
        CountDownLatch releaseCollector = new CountDownLatch(1);
        when(collector.collectAll(any())).thenAnswer(invocation -> {
            enteredCollector.countDown();
            releaseCollector.await();
            return List.of();
        });
        when(persistence.synchronize(any(), any(Instant.class), anySet()))
                .thenReturn(new NoticeSyncResult(0, 0, 0, 0, 0, 0, 0));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(service::synchronize);
            enteredCollector.await();
            assertThatThrownBy(service::synchronize)
                    .isInstanceOf(NoticeSyncAlreadyRunningException.class);
            releaseCollector.countDown();
            running.get();
        }

        when(collector.collectAll(any())).thenReturn(List.of());
        when(aggregator.aggregate(any())).thenReturn(List.of());
        when(sh.collect()).thenReturn(List.of());
        when(gh.collect()).thenReturn(List.of());
        when(hug.collect()).thenReturn(List.of());
        assertThat(service.synchronize().collectedRows()).isZero();
    }

    private NoticeResponse notice(String id, String endDate) {
        return new NoticeResponse(
                "MYHOME", id, "공고중", "공고 " + id, "LH", null,
                "2026-10-01", null, null, endDate, null,
                "https://example.test/" + id, null, null, List.of(), List.of()
        );
    }

    private MyHomeNotice row(String id) {
        return new MyHomeNotice(null, id, null, "공고중", "공고", "LH", null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
