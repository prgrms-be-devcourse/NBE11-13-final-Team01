package com.zipick.external.sh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import com.zipick.notice.dto.NoticeResponse;
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
            "경쟁률|당첨자|예비\\s*\\d*차|동호배정|서류심사|입주대상자\\s*발표|시스템.*안내"
    );
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final ExternalDocumentClient documentClient;
    private final HousingDetailEnricher detailEnricher;
    private final ShCollectorProperties properties;

    public ShNoticeCollector(
            ExternalDocumentClient documentClient,
            HousingDetailEnricher detailEnricher,
            ShCollectorProperties properties
    ) {
        this.documentClient = documentClient;
        this.detailEnricher = detailEnricher;
        this.properties = properties;
    }

    public List<NoticeSyncCommand> collect() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<ShNoticeItem> items = collectList();
        List<NoticeSyncCommand> commands = new ArrayList<>();
        for (ShNoticeItem item : items) {
            NoticeResponse basic = new NoticeResponse(
                    "SH", item.sequence(), "공고중", item.title(), "서울주택도시개발공사",
                    null, item.registeredDate().toString(), null, null, null, null,
                    item.detailUrl(), item.detailUrl(), null, List.of(), List.of()
            );
            NoticeResponse enriched = detailEnricher.enrich(basic);
            commands.add(new NoticeSyncCommand(enriched, JSON_MAPPER.writeValueAsString(Map.of(
                    "sequence", item.sequence(),
                    "title", item.title(),
                    "registeredDate", item.registeredDate().toString(),
                    "detailUrl", item.detailUrl()
            ))));
            pause();
        }
        log.info("SH 모집공고 수집 완료: 대상={}", commands.size());
        return List.copyOf(commands);
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
            String detailUrl = pageUri.resolve("view.do?multi_itm_seq=2&seq=" + sequence).toString();
            items.add(new ShNoticeItem(sequence, title, registeredDate, detailUrl));
        }
        return List.copyOf(items);
    }

    private List<ShNoticeItem> collectList() {
        LocalDate cutoff = LocalDate.now(KOREA_ZONE).minusDays(properties.lookbackDays());
        URI base = URI.create(properties.listUrl());
        List<ShNoticeItem> items = new ArrayList<>();
        for (int page = 1; page <= properties.maxPages(); page++) {
            URI pageUri = withPage(base, page);
            String html;
            try {
                html = documentClient.getText(pageUri);
            } catch (RuntimeException exception) {
                if (page == 1) {
                    throw exception;
                }
                log.warn("SH 목록 후속 페이지 수집 중단: page={}, reason={}", page, exception.getMessage());
                break;
            }
            List<ShNoticeItem> pageItems = parseList(html, pageUri, cutoff);
            items.addAll(pageItems);
            LocalDate oldest = oldestDate(html, pageUri);
            if (oldest != null && oldest.isBefore(cutoff)) {
                break;
            }
            pause();
        }
        return items.stream().distinct().toList();
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
        return StringUtils.hasText(title) && INCLUDE.matcher(title).find() && !EXCLUDE.matcher(title).find();
    }

    private URI withPage(URI base, int page) {
        String separator = base.toString().contains("?") ? "&" : "?";
        return URI.create(base + separator + "page=" + page);
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
