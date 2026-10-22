package com.zipick.external.gh;

import com.zipick.external.housingdetail.DetailedHousingMedia;
import com.zipick.external.housingdetail.DetailedHousingUnit;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailResult;
import com.zipick.external.housingdetail.HousingDetailSource;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(30)
public class GhHousingDetailSource implements HousingDetailSource {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Pattern FILE_SIZE = Pattern.compile("\\s*\\(\\d+\\s*Byte\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTRICT = Pattern.compile(
            "^(?:경기도|서울특별시|인천광역시)\\s+((?:\\S+시\\s+\\S+구)|\\S+(?:시|군|구))"
    );

    private final ExternalDocumentClient documentClient;

    public GhHousingDetailSource(ExternalDocumentClient documentClient) {
        this.documentClient = documentClient;
    }

    @Override
    public boolean supports(NoticeResponse notice) {
        if ("GH".equalsIgnoreCase(notice.source())) {
            return true;
        }
        if (!StringUtils.hasText(notice.url())) {
            return false;
        }
        URI uri = URI.create(notice.url());
        return uri.getHost() != null && uri.getHost().toLowerCase(Locale.ROOT).endsWith("gh.or.kr");
    }

    @Override
    public HousingDetailResult extract(NoticeResponse notice) {
        URI detailUri = URI.create(notice.url());
        Document document = Jsoup.parse(documentClient.getText(detailUri), detailUri.toString());
        List<NoticeAttachmentResponse> attachments = attachments(document, notice.noticeId());
        List<DetailedHousingUnit> units = new ArrayList<>();
        Map<String, List<DetailedHousingMedia>> mediaByUnit = new LinkedHashMap<>();

        int rowNumber = 0;
        for (Element section : document.select("div[id^=tab]")) {
            Element infoTable = section.selectFirst("table:has(caption:matches(사업지구별 공급정보))");
            if (infoTable == null) {
                continue;
            }
            Map<String, String> info = labelValues(infoTable);
            String complexName = first(info, "지구명", "단지명");
            String address = first(info, "소재지", "주소");
            if (!StringUtils.hasText(address)) {
                continue;
            }
            List<DetailedHousingMedia> sectionMedia = media(section, notice.noticeId());
            int supplyIndex = 0;
            for (Element row : section.select("tbody.tb_house tr.tb_house_con")) {
                List<Element> cells = row.select("td");
                if (cells.size() < 5) {
                    continue;
                }
                Element button = cells.getFirst().selectFirst("button[data-houseTyCd]");
                String housingType = button == null ? cells.getFirst().text().trim()
                        : button.attr("data-houseTyCd").trim();
                String supplyType = button == null ? null : button.text().replaceFirst(
                        "^" + Pattern.quote(housingType) + "\\s*", "").trim();
                String unitKey = "GH:" + notice.noticeId() + ":" + section.id() + ":"
                        + (++supplyIndex) + ":" + housingType;
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("complexName", complexName);
                raw.put("heatingMethod", first(info, "난방방식"));
                raw.put("expectedMoveIn", first(info, "입주예정월", "입주예정일"));
                raw.put("supplyCount", cells.get(2).text().trim());
                units.add(new DetailedHousingUnit(
                        unitKey, complexName, province(address), district(address), address,
                        null, null, null, decimal(cells.get(1).text()), null,
                        housingType, supplyType, money(cells.get(3).text()), money(cells.get(4).text()),
                        JSON_MAPPER.writeValueAsString(raw), null, null, ++rowNumber,
                        cells.size() > 5 ? cells.get(5).text().trim() : null,
                        null, null, null, null, null, null, null,
                        longValue(cells.get(2).text()), List.of()
                ));
                if (!sectionMedia.isEmpty()) {
                    mediaByUnit.put(unitKey, sectionMedia);
                }
            }
        }
        return new HousingDetailResult(units, attachments, mediaByUnit);
    }

    List<NoticeAttachmentResponse> attachments(Document document, String noticeId) {
        List<NoticeAttachmentResponse> result = new ArrayList<>();
        int index = 0;
        for (Element link : document.select("a[href*=selectFileDown]")) {
            String fileName = FILE_SIZE.matcher(link.ownText().trim()).replaceFirst("");
            if (!StringUtils.hasText(fileName)) {
                fileName = "GH 첨부파일 " + (index + 1);
            }
            String url = link.absUrl("href");
            result.add(new NoticeAttachmentResponse(
                    "GH_FILE:" + noticeId + ":" + (++index), attachmentType(fileName),
                    fileName, url, null
            ));
        }
        return List.copyOf(result);
    }

    private Map<String, String> labelValues(Element table) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element header : table.select("th")) {
            Element value = header.nextElementSibling();
            if (value != null && "td".equals(value.tagName())) {
                result.put(header.text().replaceAll("\\s+", "").trim(), value.text().trim());
            }
        }
        return result;
    }

    private List<DetailedHousingMedia> media(Element section, String noticeId) {
        List<DetailedHousingMedia> result = new ArrayList<>();
        int index = 0;
        for (Element button : section.select("button[data-show]")) {
            String targetId = button.attr("data-show").trim();
            if (!StringUtils.hasText(targetId)) {
                continue;
            }
            Element image = section.selectFirst("#" + targetId + " img[src]");
            if (image == null) {
                continue;
            }
            String label = button.text().trim();
            String rawPayload = JSON_MAPPER.writeValueAsString(Map.of(
                    "alt", image.attr("alt")
            ));
            result.add(new DetailedHousingMedia(
                    section.id(), "GH_MEDIA:" + noticeId + ":" + section.id() + ":" + (++index),
                    mediaType(label), label, image.absUrl("src"), rawPayload
            ));
        }
        return List.copyOf(result);
    }

    private String first(Map<String, String> values, String... names) {
        for (String name : names) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().contains(name) && StringUtils.hasText(entry.getValue())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String province(String address) {
        return address == null || address.isBlank() ? null : address.trim().split("\\s+")[0];
    }

    private String district(String address) {
        Matcher matcher = DISTRICT.matcher(address == null ? "" : address.trim());
        return matcher.find() ? matcher.group(1) : null;
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
        if (!StringUtils.hasText(value) || value.contains("확인")) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        try {
            return digits.isEmpty() ? null : Long.valueOf(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        try {
            return digits.isEmpty() ? null : Long.valueOf(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String attachmentType(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("공고") && !normalized.contains("주택목록")) return "ANNOUNCEMENT";
        if (normalized.contains("주택") || normalized.contains("공급")) return "HOUSING_LIST";
        if (normalized.contains("평면") || normalized.contains("도면")) return "FLOOR_PLAN";
        return "OTHER";
    }

    private String mediaType(String label) {
        if (label.contains("평면")) return "FLOOR_PLAN";
        if (label.contains("배치")) return "SITE_PLAN";
        if (label.contains("위치")) return "LOCATION_MAP";
        return "HOUSING_IMAGE";
    }
}
