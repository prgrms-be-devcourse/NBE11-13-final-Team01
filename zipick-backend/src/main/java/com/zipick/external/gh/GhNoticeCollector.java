package com.zipick.external.gh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.service.NoticeSyncCommand;
import com.zipick.notice.service.RentalNoticePolicy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GhNoticeCollector {

    private static final Logger log = LoggerFactory.getLogger(GhNoticeCollector.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE = Pattern.compile("(20\\d{2})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");
    private static final Pattern PERIOD = Pattern.compile(
            "(?:온라인접수기간|현장접수기간|접수기간|접수처\\s*운영기간)\\s*[:：]?\\s*"
                    + "(20\\d{2}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}).{0,30}?[~-]\\s*"
                    + "(20\\d{2}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2})"
    );
    private static final URI RENTAL_LIST = URI.create(
            "https://apply.gh.or.kr/sb/sr/sr7150/selectPbancRentHouseList.do"
    );
    private static final URI PURCHASE_LIST = URI.create(
            "https://apply.gh.or.kr/sb/sr/sr7155/selectPbancRentHouseList.do"
    );

    private final ExternalDocumentClient documentClient;
    private final HousingDetailEnricher detailEnricher;
    private final GhCollectorProperties properties;

    public GhNoticeCollector(
            ExternalDocumentClient documentClient,
            HousingDetailEnricher detailEnricher,
            GhCollectorProperties properties
    ) {
        this.documentClient = documentClient;
        this.detailEnricher = detailEnricher;
        this.properties = properties;
    }

    public List<NoticeSyncCommand> collect() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        Map<String, GhNoticeItem> candidates = new LinkedHashMap<>();
        collectList(RENTAL_LIST, candidates);
        collectList(PURCHASE_LIST, candidates);

        LocalDate today = LocalDate.now(KOREA_ZONE);
        List<NoticeSyncCommand> commands = new ArrayList<>();
        for (GhNoticeItem item : candidates.values()) {
            if (RentalNoticePolicy.isExcluded(item.title(), item.housingType())) {
                continue;
            }
            DateRange period = resolvePeriod(item);
            if (period != null && period.end() != null && period.end().isBefore(today)) {
                log.info("GH 접수 마감일이 지난 공고를 제외합니다: id={}, title={}",
                        item.noticeId(), item.title());
                continue;
            }
            NoticeResponse basic = new NoticeResponse(
                    "GH", item.noticeId(), "공고중", item.title(), "경기주택도시공사",
                    null, text(item.announcedAt()), null,
                    period == null ? null : text(period.start()),
                    period == null ? null : text(period.end()),
                    item.housingType(), item.detailUrl(), item.detailUrl(), null, List.of(), List.of()
            );
            NoticeResponse enriched = detailEnricher.enrich(basic);
            commands.add(new NoticeSyncCommand(enriched, rawPayload(item, period)));
            pause();
        }
        log.info("GH 모집공고 수집 완료: 대상={}", commands.size());
        return List.copyOf(commands);
    }

    private String rawPayload(GhNoticeItem item, DateRange period) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("noticeId", item.noticeId());
        payload.put("title", item.title());
        payload.put("housingType", nullToEmpty(item.housingType()));
        payload.put("region", nullToEmpty(item.region()));
        if (period != null && period.start() != null) {
            payload.put("applicationStartDate", period.start().toString());
        }
        if (period != null && period.end() != null) {
            payload.put("applicationEndDate", period.end().toString());
        }
        payload.put("detailUrl", item.detailUrl());
        return JSON_MAPPER.writeValueAsString(payload);
    }

    List<GhNoticeItem> parseList(String html, URI listUri) {
        Document document = Jsoup.parse(html, listUri.toString());
        List<GhNoticeItem> result = new ArrayList<>();
        for (Element row : document.select("#sub_content tbody tr")) {
            Element link = row.selectFirst("a.text_cut[data-pbancNo]");
            if (link == null) {
                continue;
            }
            List<Element> cells = row.select("td");
            if (cells.size() < 7) {
                continue;
            }
            String id = link.attr("data-pbancNo").trim();
            String kind = link.attr("data-pbancKndCd").trim();
            String type = link.attr("data-bizTyNm").trim();
            String status = cells.size() > 7 ? cells.get(7).text().trim() : null;
            LocalDate announced = parseDate(cells.get(5).text());
            LocalDate end = parseDate(cells.get(6).text());
            String detailPath = listUri.getPath().replaceAll("/[^/]+$", "/selectPbancDetailView.do");
            String detailUrl = listUri.resolve(detailPath + "?pbancNo=" + encode(id)
                    + "&pbancKndCd=" + encode(kind) + "&bizTyNm=" + encode(type)).toString();
            result.add(new GhNoticeItem(
                    id, link.text().trim(), type, cells.get(3).text().trim(), announced,
                    end, status, detailUrl
            ));
        }
        return List.copyOf(result);
    }

    DateRange parsePeriod(String html) {
        String text = Jsoup.parse(html).text();
        Matcher matcher = PERIOD.matcher(text);
        DateRange selected = null;
        while (matcher.find()) {
            LocalDate start = parseDate(matcher.group(1));
            LocalDate end = parseDate(matcher.group(2));
            if (start != null && end != null && (selected == null || end.isAfter(selected.end()))) {
                selected = new DateRange(start, end);
            }
        }
        return selected;
    }

    private void collectList(URI base, Map<String, GhNoticeItem> result) {
        for (int page = 1; page <= properties.maxPages(); page++) {
            URI pageUri = page == 1 ? base : URI.create(base + "?pageIndex=" + page);
            List<GhNoticeItem> pageItems = parseList(documentClient.getText(pageUri), base);
            if (pageItems.isEmpty()) {
                break;
            }
            boolean hasPossibleActive = false;
            int previousSize = result.size();
            LocalDate today = LocalDate.now(KOREA_ZONE);
            for (GhNoticeItem item : pageItems) {
                if (!isClosed(item.status()) && (item.applyEndAt() == null || !item.applyEndAt().isBefore(today))) {
                    result.putIfAbsent(item.noticeId(), item);
                    hasPossibleActive = true;
                }
            }
            if (!hasPossibleActive) {
                break;
            }
            if (result.size() == previousSize) {
                break;
            }
            pause();
        }
    }

    private DateRange resolvePeriod(GhNoticeItem item) {
        DateRange detailPeriod = parsePeriod(documentClient.getText(URI.create(item.detailUrl())));
        if (detailPeriod != null) {
            return detailPeriod;
        }
        return item.applyEndAt() == null ? null
                : new DateRange(item.announcedAt() == null ? item.applyEndAt() : item.announcedAt(), item.applyEndAt());
    }

    private boolean isClosed(String status) {
        return StringUtils.hasText(status)
                && (status.contains("마감") || status.contains("종료") || status.contains("취소"));
    }

    private List<LocalDate> dates(String value) {
        List<LocalDate> result = new ArrayList<>();
        Matcher matcher = DATE.matcher(value);
        while (matcher.find()) {
            LocalDate parsed = parseDate(matcher.group());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1) + "-" + two(matcher.group(2)) + "-" + two(matcher.group(3)),
                    DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String two(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void pause() {
        if (properties.requestDelayMillis() == 0) {
            return;
        }
        try {
            Thread.sleep(properties.requestDelayMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GH 수집 대기 중 중단되었습니다.", exception);
        }
    }

    record DateRange(LocalDate start, LocalDate end) {
    }
}
