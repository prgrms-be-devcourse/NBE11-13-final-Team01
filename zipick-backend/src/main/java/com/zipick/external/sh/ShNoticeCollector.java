package com.zipick.external.sh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.service.RentalNoticePolicy;
import com.zipick.notice.service.NoticeSyncCommand;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShNoticeCollector {

    private static final Logger log = LoggerFactory.getLogger(ShNoticeCollector.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Pattern DETAIL_SEQUENCE = Pattern.compile("getDetailView\\('(?<seq>\\d+)'\\)");
    private static final Pattern INCLUDE = Pattern.compile("입주자\\s*모집|잔여세대\\s*모집|추가\\s*모집");
    private static final Pattern EXCLUDE = Pattern.compile(
            "경쟁률|당첨자|예비\\s*\\d*차|동호배정|서류심사|입주대상자\\s*발표|"
                    + "접수\\s*결과|결과\\s*안내|면접평가\\s*대상자\\s*발표|접수마감\\s*(?:안내|공지)|시스템.*안내"
    );
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final ExternalDocumentClient documentClient;
    private final HousingDetailEnricher detailEnricher;
    private final ShCollectorProperties properties;
    private final ShApplicationPeriodParser periodParser;

    public ShNoticeCollector(
            ExternalDocumentClient documentClient,
            HousingDetailEnricher detailEnricher,
            ShCollectorProperties properties,
            ShApplicationPeriodParser periodParser
    ) {
        this.documentClient = documentClient;
        this.detailEnricher = detailEnricher;
        this.properties = properties;
        this.periodParser = periodParser;
    }

    public List<NoticeSyncCommand> collect() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<ShNoticeItem> items = collectList();
        List<NoticeSyncCommand> commands = new ArrayList<>();
        for (ShNoticeItem item : items) {
            String detailHtml = documentClient.getText(URI.create(item.detailUrl()));
            ShApplicationPeriod period = periodParser.parse(detailHtml).orElse(null);
            LocalDate today = LocalDate.now(KOREA_ZONE);
            if (period != null && !period.isNotExpiredOn(today)) {
                log.info("SH 접수 마감일이 지난 공고를 제외합니다: seq={}, start={}, end={}, title={}",
                        item.sequence(), period.startDate(), period.endDate(), item.title());
                continue;
            }
            NoticeResponse basic = new NoticeResponse(
                    "SH", item.sequence(), "공고중", item.title(), "서울주택도시개발공사",
                    null, item.registeredDate().toString(), null,
                    period == null ? null : period.startDate().toString(),
                    period == null ? null : period.endDate().toString(), null,
                    item.detailUrl(), item.detailUrl(), null, List.of(), List.of()
            );
            NoticeResponse enriched = detailEnricher.enrich(basic);
            commands.add(new NoticeSyncCommand(enriched, rawPayload(item, period)));
            pause();
        }
        log.info("SH 모집공고 수집 완료: 대상={}", commands.size());
        return List.copyOf(commands);
    }

    private String rawPayload(ShNoticeItem item, ShApplicationPeriod period) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("sequence", item.sequence());
        payload.put("title", item.title());
        payload.put("registeredDate", item.registeredDate().toString());
        if (period != null) {
            payload.put("applicationStartDate", period.startDate().toString());
            payload.put("applicationEndDate", period.endDate().toString());
        }
        payload.put("detailUrl", item.detailUrl());
        return JSON_MAPPER.writeValueAsString(payload);
    }

    List<ShNoticeItem> parseList(String html, URI pageUri, LocalDate cutoff) {
        Document document = Jsoup.parse(html, pageUri.toString());
        List<ShNoticeItem> items = new ArrayList<>();
        for (Element row : document.select("#listTb tbody tr")) {
            Element link = row.selectFirst("a[onclick*=getDetailView]");
            if (link == null) {
                continue;
            }
            String title = link.text().trim();
            if (!isRecruitmentNotice(title)) {
                continue;
            }
            Matcher matcher = DETAIL_SEQUENCE.matcher(link.attr("onclick"));
            if (!matcher.find()) {
                continue;
            }
            LocalDate registeredDate = findRegisteredDate(row.select("td"));
            if (registeredDate == null || registeredDate.isBefore(cutoff)) {
                continue;
            }
            String sequence = matcher.group("seq");
            String multiItemSequence = queryParameter(pageUri, "multi_itm_seq", "2");
            String detailUrl = pageUri.resolve(
                    "view.do?multi_itm_seq=" + multiItemSequence + "&seq=" + sequence
            ).toString();
            items.add(new ShNoticeItem(sequence, title, registeredDate, detailUrl));
        }
        return List.copyOf(items);
    }

    private List<ShNoticeItem> collectList() {
        LocalDate cutoff = LocalDate.now(KOREA_ZONE).minusDays(properties.lookbackDays());
        List<ShNoticeItem> items = new ArrayList<>();
        for (URI base : listUris()) {
            collectList(base, cutoff, items);
        }
        return items.stream().distinct().toList();
    }

    private void collectList(URI base, LocalDate cutoff, List<ShNoticeItem> items) {
        for (int page = 1; page <= properties.maxPages(); page++) {
            URI pageUri = withPage(base, page);
            String html;
            try {
                html = documentClient.getText(pageUri);
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "SH 목록 수집을 완료하지 못했습니다: page=" + page, exception
                );
            }
            List<ShNoticeItem> pageItems = parseList(html, pageUri, cutoff);
            items.addAll(pageItems);
            LocalDate oldest = oldestDate(html, pageUri);
            if (oldest != null && oldest.isBefore(cutoff)) {
                break;
            }
            pause();
        }
    }

    private List<URI> listUris() {
        return List.of(URI.create(properties.listUrl()));
    }

    private LocalDate oldestDate(String html, URI pageUri) {
        Document document = Jsoup.parse(html, pageUri.toString());
        LocalDate oldest = null;
        for (Element row : document.select("#listTb tbody tr")) {
            LocalDate date = findRegisteredDate(row.select("td"));
            if (date != null && (oldest == null || date.isBefore(oldest))) {
                oldest = date;
            }
        }
        return oldest;
    }

    private LocalDate findRegisteredDate(Elements cells) {
        for (Element cell : cells) {
            String value = cell.text().trim();
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                continue;
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isRecruitmentNotice(String title) {
        return StringUtils.hasText(title)
                && INCLUDE.matcher(title).find()
                && !EXCLUDE.matcher(title).find()
                && !RentalNoticePolicy.isExcluded(title);
    }

    private URI withPage(URI base, int page) {
        String separator = base.toString().contains("?") ? "&" : "?";
        return URI.create(base + separator + "page=" + page);
    }

    private String queryParameter(URI uri, String name, String defaultValue) {
        if (uri.getQuery() == null) {
            return defaultValue;
        }
        for (String parameter : uri.getQuery().split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                return pair[1];
            }
        }
        return defaultValue;
    }

    private void pause() {
        if (properties.requestDelayMillis() == 0) {
            return;
        }
        try {
            Thread.sleep(properties.requestDelayMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SH 수집 대기 중 중단되었습니다.", exception);
        }
    }
}
