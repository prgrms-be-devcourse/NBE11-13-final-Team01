package com.zipick.notice.service;

import com.zipick.external.myhome.client.MyHomeNoticeCollector;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.external.sh.ShNoticeCollector;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSyncResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MyHomeSyncService {

    private static final Logger log = LoggerFactory.getLogger(MyHomeSyncService.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ReentrantLock syncLock = new ReentrantLock();
    private final MyHomeNoticeCollector collector;
    private final MyHomeNoticeAggregator aggregator;
    private final HousingDetailEnricher detailEnricher;
    private final ShNoticeCollector shNoticeCollector;
    private final NoticeSyncPersistenceService persistenceService;
    private final HousingGeocodingService geocodingService;

    public MyHomeSyncService(
            MyHomeNoticeCollector collector,
            MyHomeNoticeAggregator aggregator,
            HousingDetailEnricher detailEnricher,
            ShNoticeCollector shNoticeCollector,
            NoticeSyncPersistenceService persistenceService,
            HousingGeocodingService geocodingService
    ) {
        this.collector = collector;
        this.aggregator = aggregator;
        this.detailEnricher = detailEnricher;
        this.shNoticeCollector = shNoticeCollector;
        this.persistenceService = persistenceService;
        this.geocodingService = geocodingService;
    }

    public NoticeSyncResponse synchronize() {
        if (!syncLock.tryLock()) {
            throw new NoticeSyncAlreadyRunningException();
        }

        Instant startedAt = Instant.now();
        try {
            List<MyHomeNotice> rows = collector.collectAll(allNoticesQuery());
            List<NoticeSyncCommand> shCommands = collectShSafely();
            List<NoticeSyncCommand> commands = new ArrayList<>(createCommands(rows));
            commands.addAll(shCommands);
            NoticeSyncResult result = persistenceService.synchronize(commands, startedAt);
            geocodingService.enrichMissingCoordinates();
            return NoticeSyncResponse.from(rows.size() + shCommands.size(), result, startedAt, Instant.now());
        } finally {
            syncLock.unlock();
        }
    }

    private List<NoticeSyncCommand> collectShSafely() {
        try {
            return shNoticeCollector.collect();
        } catch (RuntimeException exception) {
            // 한 기관의 장애로 이미 수집한 마이홈/LH 데이터까지 저장하지 못하는 일을 막는다.
            log.error("SH 공고 수집을 건너뜁니다: {}", exception.getMessage(), exception);
            return List.of();
        }
    }

    private MyHomeNoticeQuery allNoticesQuery() {
        return new MyHomeNoticeQuery(
                null, null, 1, 100,
                null, null, null, null, null, null
        );
    }

    private List<NoticeSyncCommand> createCommands(List<MyHomeNotice> rows) {
        Map<String, List<MyHomeNotice>> rowsByNoticeId = new LinkedHashMap<>();
        for (MyHomeNotice row : rows) {
            if (row != null && StringUtils.hasText(row.pblancId())) {
                rowsByNoticeId.computeIfAbsent(row.pblancId().trim(), ignored -> new ArrayList<>()).add(row);
            }
        }

        Map<String, NoticeResponse> aggregatedById = new LinkedHashMap<>();
        for (NoticeResponse notice : aggregator.aggregate(rows)) {
            aggregatedById.put(notice.noticeId(), detailEnricher.enrich(notice));
        }

        List<NoticeSyncCommand> commands = new ArrayList<>();
        for (Map.Entry<String, List<MyHomeNotice>> entry : rowsByNoticeId.entrySet()) {
            NoticeResponse notice = aggregatedById.get(entry.getKey());
            if (notice != null) {
                commands.add(new NoticeSyncCommand(notice, JSON_MAPPER.writeValueAsString(entry.getValue())));
            }
        }
        return List.copyOf(commands);
    }
}
