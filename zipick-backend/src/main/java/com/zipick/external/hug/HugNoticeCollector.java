package com.zipick.external.hug;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.service.NoticeSyncCommand;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
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
public class HugNoticeCollector {

    private static final Logger log = LoggerFactory.getLogger(HugNoticeCollector.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE = Pattern.compile("(20\\d{2})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");

    private final ExternalDocumentClient documentClient;
    private final HugCollectorProperties properties;

    public HugNoticeCollector(ExternalDocumentClient documentClient, HugCollectorProperties properties) {
        this.documentClient = documentClient;
        this.properties = properties;
    }

    public List<NoticeSyncCommand> collect() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        for (int page = 1; page <= properties.maxPages(); page++) {
            String separator = properties.listUrl().contains("?") ? "&" : "?";
            URI pageUri = URI.create(properties.listUrl() + separator + "cur_page=" + page);
            String html = documentClient.getText(pageUri);
            List<Row> rows = parseRows(html);
            if (rows.isEmpty()) {
                if (page == 1 && !isValidEmptyResult(html)) {
                    throw new IllegalStateException("HUG 조회 결과를 확인할 수 없습니다.");
                }
                break;
            }
            for (Row row : rows) {
                groups.computeIfAbsent(row.groupKey(), ignored -> new Group(row.announcedAt(), row.period()))
                        .rows().add(row);
            }
            if (rows.stream().anyMatch(row -> row.period().end() != null
                    && row.period().end().isBefore(LocalDate.now(KOREA_ZONE)))) {
                break;
            }
            pause();
        }

        LocalDate today = LocalDate.now(KOREA_ZONE);
        List<NoticeSyncCommand> commands = new ArrayList<>();
        for (Group group : groups.values()) {
            if (group.period().end() != null && group.period().end().isBefore(today)) {
                continue;
            }
            String id = group.announcedAt().toString() + "-" + group.period().start() + "-" + group.period().end();
            List<NoticeHousingResponse> houses = group.rows().stream().map(row -> toHousing(id, row)).toList();
            String title = group.announcedAt() + " HUG 든든전세주택 입주자 모집공고";
            NoticeResponse notice = new NoticeResponse(
                    "HUG", id, "공고중", title, "주택도시보증공사", null,
                    group.announcedAt().toString(), null, text(group.period().start()),
                    text(group.period().end()), "든든전세주택", properties.listUrl(),
                    properties.listUrl(), null, houses, group.rows().getFirst().attachments()
            );
            commands.add(new NoticeSyncCommand(notice, JSON_MAPPER.writeValueAsString(group.rows())));
        }
        log.info("HUG 모집공고 수집 완료: 공고={}, 주택={}", commands.size(),
                commands.stream().mapToInt(command -> command.notice().houses().size()).sum());
        return List.copyOf(commands);
    }

    List<Row> parseRows(String html) {
        Document document = Jsoup.parse(html, properties.listUrl());
        List<NoticeAttachmentResponse> attachments = attachments(document);
        List<Row> result = new ArrayList<>();
        int rowNumber = 0;
        for (Element row : document.select("table.d_list tbody tr, table.mb.d_list tbody tr")) {
            List<Element> cells = row.select("td");
            if (cells.size() < 10) {
                continue;
            }
            LocalDate announced = firstDate(cells.get(1).text());
            List<LocalDate> periodDates = dates(cells.get(2).text());
            if (announced == null) {
                continue;
            }
            LocalDate start = periodDates.isEmpty() ? null : periodDates.getFirst();
            LocalDate end = periodDates.size() < 2 ? null : periodDates.get(1);
            result.add(new Row(
                    ++rowNumber, announced, new DateRange(start, end),
                    cells.get(3).text().trim(), cells.get(4).text().trim(), cells.get(5).text().trim(),
                    cells.get(6).text().trim(), cells.get(7).text().trim(), decimal(cells.get(8).text()),
                    money(cells.get(9).text()), cells.size() > 10 ? longValue(cells.get(10).text()) : null,
                    attachments
            ));
        }
        return List.copyOf(result);
    }

    private NoticeHousingResponse toHousing(String noticeId, Row row) {
        String key = "HUG:" + noticeId + ":" + row.rowNumber();
        NoticeSupplyResponse supply = new NoticeSupplyResponse(
                key + ":SUPPLY", null, row.housingType(), row.purchaseType(), null, null,
                null, null, row.exclusiveArea(), 1L, "1", row.deposit(), null,
                null, null, null, JSON_MAPPER.writeValueAsString(row)
        );
        return new NoticeHousingResponse(
                key, null, row.province(), row.district(), row.address(), null, null,
                null, null, null, null, null, List.of(supply), List.of()
        );
    }

    private List<NoticeAttachmentResponse> attachments(Document document) {
        List<NoticeAttachmentResponse> result = new ArrayList<>();
        int index = 0;
        for (Element link : document.select("a[href$=.pdf], a[href*=homepage_file_upload]")) {
            String url = link.absUrl("href");
            if (!StringUtils.hasText(url) || result.stream().anyMatch(item -> item.sourceUrl().equals(url))) {
                continue;
            }
            String path = URI.create(url).getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            result.add(new NoticeAttachmentResponse(
                    "HUG_FILE:" + (++index), "ANNOUNCEMENT", fileName, url, null
            ));
        }
        return List.copyOf(result);
    }

    private boolean isValidEmptyResult(String html) {
        Document document = Jsoup.parse(html);
        String text = document.text().replaceAll("\\s+", " ");
        return document.selectFirst("table.d_list, table.mb.d_list") != null
                && (text.contains("총 0건") || text.contains("등록된 게시물이 없습니다"));
    }

    private List<LocalDate> dates(String value) {
        List<LocalDate> result = new ArrayList<>();
        Matcher matcher = DATE.matcher(value);
        while (matcher.find()) {
            LocalDate parsed = parseDate(matcher.group());
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private LocalDate firstDate(String value) {
        List<LocalDate> values = dates(value);
        return values.isEmpty() ? null : values.getFirst();
    }

    private LocalDate parseDate(String value) {
        Matcher matcher = DATE.matcher(value == null ? "" : value);
        if (!matcher.find()) return null;
        try {
            return LocalDate.parse(matcher.group(1) + "-" + two(matcher.group(2)) + "-" + two(matcher.group(3)),
                    DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private String two(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private BigDecimal decimal(String value) {
        try {
            Matcher matcher = Pattern.compile("[\\d.]+").matcher(value.replace(",", ""));
            return matcher.find() ? new BigDecimal(matcher.group()) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long money(String value) {
        return longValue(value);
    }

    private Long longValue(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        try {
            return digits.isEmpty() ? null : Long.valueOf(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void pause() {
        if (properties.requestDelayMillis() == 0) return;
        try {
            Thread.sleep(properties.requestDelayMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HUG 수집 대기 중 중단되었습니다.", exception);
        }
    }

    record DateRange(LocalDate start, LocalDate end) {
    }

    record Row(
            int rowNumber,
            LocalDate announcedAt,
            DateRange period,
            String province,
            String district,
            String address,
            String housingType,
            String purchaseType,
            BigDecimal exclusiveArea,
            Long deposit,
            Long applicantCount,
            List<NoticeAttachmentResponse> attachments
    ) {
        String groupKey() {
            return announcedAt + ":" + period.start() + ":" + period.end();
        }
    }

    private record Group(LocalDate announcedAt, DateRange period, List<Row> rows) {
        private Group(LocalDate announcedAt, DateRange period) {
            this(announcedAt, period, new ArrayList<>());
        }
    }
}
