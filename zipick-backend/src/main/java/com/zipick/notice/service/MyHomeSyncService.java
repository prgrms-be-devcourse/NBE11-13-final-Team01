package com.zipick.notice.service;

import com.zipick.external.myhome.client.MyHomeNoticeCollector;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.external.ai.GeminiNoticeNormalizationService;
import com.zipick.external.gh.GhNoticeCollector;
import com.zipick.external.hug.HugNoticeCollector;
import com.zipick.external.sh.ShNoticeCollector;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSyncResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class MyHomeSyncService {

    private static final Logger log = LoggerFactory.getLogger(MyHomeSyncService.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE,
            DateTimeFormatter.ofPattern("yyyy.MM.dd")
    );

    private final ReentrantLock syncLock = new ReentrantLock();
    private final MyHomeNoticeCollector collector;
    private final MyHomeNoticeAggregator aggregator;
    private final HousingDetailEnricher detailEnricher;
    private final ShNoticeCollector shNoticeCollector;
    private final GhNoticeCollector ghNoticeCollector;
    private final HugNoticeCollector hugNoticeCollector;
    private final NoticeSyncPersistenceService persistenceService;
    private final HousingGeocodingService geocodingService;
    private final GeminiNoticeNormalizationService normalizationService;

    public MyHomeSyncService(
            MyHomeNoticeCollector collector,
            MyHomeNoticeAggregator aggregator,
            HousingDetailEnricher detailEnricher,
            ShNoticeCollector shNoticeCollector,
            GhNoticeCollector ghNoticeCollector,
            HugNoticeCollector hugNoticeCollector,
            NoticeSyncPersistenceService persistenceService,
            HousingGeocodingService geocodingService,
            GeminiNoticeNormalizationService normalizationService
    ) {
        this.collector = collector;
        this.aggregator = aggregator;
        this.detailEnricher = detailEnricher;
        this.shNoticeCollector = shNoticeCollector;
        this.ghNoticeCollector = ghNoticeCollector;
        this.hugNoticeCollector = hugNoticeCollector;
        this.persistenceService = persistenceService;
        this.geocodingService = geocodingService;
        this.normalizationService = normalizationService;
    }

    public NoticeSyncResponse synchronize() {
        if (!syncLock.tryLock()) {
            throw new NoticeSyncAlreadyRunningException();
        }

        Instant startedAt = Instant.now();
        try {
            List<MyHomeNotice> rows = collector.collectAll(allNoticesQuery());
            SourceCollection shCollection = collectSafely("SH", shNoticeCollector::collect);
            SourceCollection ghCollection = collectSafely("GH", ghNoticeCollector::collect);
            SourceCollection hugCollection = collectSafely("HUG", hugNoticeCollector::collect);
            List<NoticeSyncCommand> commands = new ArrayList<>(createCommands(rows));
            commands.addAll(shCollection.commands());
            commands.addAll(ghCollection.commands());
            commands.addAll(hugCollection.commands());
            Set<String> completedSources = new LinkedHashSet<>();
            // 마이홈 API가 정상 응답했으면 API로 관리하는 기관의 소실 공고도 마감한다.
            completedSources.addAll(Set.of("LH", "BMC", "GBDC", "MYHOME"));
            if (shCollection.completed()) completedSources.add("SH");
            if (ghCollection.completed()) completedSources.add("GH");
            if (hugCollection.completed()) completedSources.add("HUG");
            NoticeSyncResult result = persistenceService.synchronize(commands, startedAt, completedSources);
            // 실제 저장된 공고만 대기열에 넣어 중복 제거된 공고가 영구 RETRY로 남지 않게 한다.
            normalizationService.enqueue(commands);
            normalizationService.normalizePending();
            geocodingService.enrichMissingCoordinates();
            int collectedRows = rows.size() + shCollection.commands().size()
                    + ghCollection.commands().size() + hugCollection.commands().size();
            return NoticeSyncResponse.from(collectedRows, result, startedAt, Instant.now());
        } finally {
            syncLock.unlock();
        }
    }

    private SourceCollection collectSafely(String source, Supplier<List<NoticeSyncCommand>> collector) {
        try {
            return new SourceCollection(collector.get(), true);
        } catch (RuntimeException exception) {
            // 한 기관의 장애로 이미 수집한 마이홈/LH 데이터까지 저장하지 못하는 일을 막는다.
            log.error("{} 공고 수집을 건너뜁니다: {}", source, exception.getMessage(), exception);
            return new SourceCollection(List.of(), false);
        }
    }

    private record SourceCollection(List<NoticeSyncCommand> commands, boolean completed) {
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
            if (isNotExpired(notice) && isRentalNotice(notice)) {
                aggregatedById.put(notice.noticeId(), detailEnricher.enrich(notice));
            }
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

    private boolean isNotExpired(NoticeResponse notice) {
        LocalDate endDate = parseDate(notice.applicationEndDate());
        return endDate == null || !endDate.isBefore(LocalDate.now(KOREA_ZONE));
    }

    private boolean isRentalNotice(NoticeResponse notice) {
        List<String> values = new ArrayList<>();
        values.add(notice.title());
        values.add(notice.reference());
        notice.houses().forEach(housing -> housing.supplies().forEach(supply -> {
            values.add(supply.houseTypeName());
            values.add(supply.supplyTypeName());
        }));
        return !RentalNoticePolicy.isExcluded(values.toArray(String[]::new));
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replace('/', '-');
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // 다음 날짜 형식으로 확인한다.
            }
        }
        return null;
    }
}
