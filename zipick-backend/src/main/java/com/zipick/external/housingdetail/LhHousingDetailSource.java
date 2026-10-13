package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.support.AddressNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(10)
public class LhHousingDetailSource implements HousingDetailSource {

    private static final Logger log = LoggerFactory.getLogger(LhHousingDetailSource.class);

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile(
            "JSON\\.parse\\('(?<json>\\[\\{.*?}])'\\)",
            Pattern.DOTALL
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile(
            "fileDownLoad\\('(?<id>\\d+)'\\);?\\\">(?<name>[^<]+)</a>",
            Pattern.CASE_INSENSITIVE
    );
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ExternalDocumentClient documentClient;
    private final LhSpreadsheetHousingParser spreadsheetParser;
    private final LhPdfHousingParser pdfParser;

    public LhHousingDetailSource(
            ExternalDocumentClient documentClient,
            LhSpreadsheetHousingParser spreadsheetParser,
            LhPdfHousingParser pdfParser
    ) {
        this.documentClient = documentClient;
        this.spreadsheetParser = spreadsheetParser;
        this.pdfParser = pdfParser;
    }

    @Override
    public boolean supports(NoticeResponse notice) {
        if (!StringUtils.hasText(notice.url())) {
            return false;
        }
        URI uri = URI.create(notice.url());
        return uri.getHost() != null && uri.getHost().toLowerCase(Locale.ROOT).endsWith("apply.lh.or.kr");
    }

    @Override
    public List<DetailedHousingUnit> extract(NoticeResponse notice) {
        URI detailUri = URI.create(notice.url());
        String html = documentClient.getText(detailUri);
        List<DetailedHousingUnit> embeddedUnits = extractEmbeddedJson(html);
        if (!embeddedUnits.isEmpty()) {
            boolean needsAttachment = embeddedUnits.stream()
                    .anyMatch(unit -> !isSpecificComplexName(unit.complexName())
                            || unit.exclusiveArea() == null
                            || unit.rentDeposit() == null
                            || unit.monthlyRent() == null);
            if (!needsAttachment) {
                return embeddedUnits;
            }
            return mergeAttachmentDetails(embeddedUnits, extractAttachments(detailUri, html));
        }

        return extractAttachments(detailUri, html);
    }

    private List<DetailedHousingUnit> extractEmbeddedJson(String html) {
        List<Map<String, Object>> bestCandidate = List.of();
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(html);
        while (matcher.find()) {
            List<Map<String, Object>> candidate = parseArray(matcher.group("json"));
            long locatedRows = candidate.stream().filter(row -> text(row, "adr") != null).count();
            long bestLocatedRows = bestCandidate.stream().filter(row -> text(row, "adr") != null).count();
            if (locatedRows > bestLocatedRows) {
                bestCandidate = candidate;
            }
        }

        List<DetailedHousingUnit> units = new ArrayList<>();
        for (Map<String, Object> row : bestCandidate) {
            String address = text(row, "adr");
            if (!StringUtils.hasText(address)) {
                continue;
            }
            String unitNumber = text(row, "hoNo");
            String key = firstText(text(row, "hoAdmNo"), text(row, "ltrUntNo"));
            if (!StringUtils.hasText(key)) {
                key = address + ":" + unitNumber;
            }
            units.add(new DetailedHousingUnit(
                    "LH_DETAIL:" + key.trim(),
                    specificComplexName(text(row, "sbdLgoNm")),
                    firstAddressPart(address, 0),
                    firstText(text(row, "sggCdNm1"), firstAddressPart(address, 1)),
                    normalizeAddress(address),
                    text(row, "dngHsNo"),
                    unitNumber,
                    integer(row.get("vldVlNm")),
                    decimal(row.get("ddoAr")),
                    integer(row.get("rmCnt")),
                    text(row, "htyDsNm"),
                    null,
                    null,
                    null,
                    JSON_MAPPER.writeValueAsString(row)
            ));
        }

        validateExpectedCount(bestCandidate, units);
        return List.copyOf(units);
    }

    private List<DetailedHousingUnit> mergeAttachmentDetails(
            List<DetailedHousingUnit> embedded,
            List<DetailedHousingUnit> attachments
    ) {
        Map<String, DetailedHousingUnit> unitsByAddressAndUnit = new java.util.LinkedHashMap<>();
        Map<String, List<DetailedHousingUnit>> unitsByAddress = new java.util.LinkedHashMap<>();
        for (DetailedHousingUnit unit : attachments) {
            if (!StringUtils.hasText(unit.address())) {
                continue;
            }
            String addressKey = AddressNormalizer.canonicalize(unit.address());
            unitsByAddress.computeIfAbsent(addressKey, ignored -> new ArrayList<>()).add(unit);
            unitsByAddressAndUnit.putIfAbsent(detailKey(unit), unit);
        }

        return embedded.stream()
                .map(unit -> mergeAttachmentDetail(
                        unit,
                        findAttachmentUnit(unit, unitsByAddressAndUnit, unitsByAddress)
                ))
                .toList();
    }

    private DetailedHousingUnit findAttachmentUnit(
            DetailedHousingUnit embedded,
            Map<String, DetailedHousingUnit> unitsByAddressAndUnit,
            Map<String, List<DetailedHousingUnit>> unitsByAddress
    ) {
        DetailedHousingUnit exact = unitsByAddressAndUnit.get(detailKey(embedded));
        if (exact != null) {
            return exact;
        }
        List<DetailedHousingUnit> sameAddress = unitsByAddress.get(
                AddressNormalizer.canonicalize(embedded.address())
        );
        return sameAddress != null && sameAddress.size() == 1 ? sameAddress.getFirst() : null;
    }

    private DetailedHousingUnit mergeAttachmentDetail(
            DetailedHousingUnit embedded,
            DetailedHousingUnit attachment
    ) {
        if (attachment == null) {
            return embedded;
        }
        return new DetailedHousingUnit(
                embedded.sourceUnitKey(),
                firstText(specificComplexName(embedded.complexName()),
                        specificComplexName(attachment.complexName())),
                firstText(embedded.provinceName(), attachment.provinceName()),
                firstText(embedded.districtName(), attachment.districtName()),
                firstText(embedded.address(), attachment.address()),
                firstText(embedded.buildingDong(), attachment.buildingDong()),
                firstText(embedded.unitNumber(), attachment.unitNumber()),
                firstValue(embedded.floor(), attachment.floor()),
                firstValue(embedded.exclusiveArea(), attachment.exclusiveArea()),
                firstValue(embedded.roomCount(), attachment.roomCount()),
                firstText(embedded.housingType(), attachment.housingType()),
                firstText(embedded.supplyType(), attachment.supplyType()),
                firstValue(embedded.rentDeposit(), attachment.rentDeposit()),
                firstValue(embedded.monthlyRent(), attachment.monthlyRent()),
                embedded.rawPayload()
        );
    }

    private String detailKey(DetailedHousingUnit unit) {
        return AddressNormalizer.canonicalize(unit.address()) + "|" + normalizeUnitNumber(unit.unitNumber());
    }

    private String normalizeUnitNumber(String value) {
        return StringUtils.hasText(value)
                ? value.replaceAll("[^가-힣A-Za-z0-9]", "").replaceFirst("호$", "")
                : "";
    }

    private <T> T firstValue(T first, T second) {
        return first != null ? first : second;
    }

    private String specificComplexName(String value) {
        if (!isSpecificComplexName(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean isSpecificComplexName(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        return !normalized.matches("매입다가구")
                && !normalized.matches("매입(?:다가구|임대)?\\([^)]*\\)")
                && !normalized.matches("(?:기존주택)?매입임대(?:주택)?");
    }

    private List<DetailedHousingUnit> extractAttachments(URI detailUri, String html) {
        List<LhAttachment> attachments = findAttachments(html).stream()
                .filter(this::isHousingListCandidate)
                .sorted(Comparator.comparingInt(this::attachmentPriority).reversed())
                .toList();

        for (LhAttachment attachment : attachments) {
            try {
                URI downloadUri = detailUri.resolve("/lhapply/lhFile.do?fileid=" + attachment.fileId());
                byte[] bytes = documentClient.getBytes(downloadUri);
                List<DetailedHousingUnit> units = parseAttachment(attachment, bytes);
                if (!units.isEmpty()) {
                    return units;
                }
            } catch (RuntimeException exception) {
                log.warn("LH 첨부 주택목록 파싱 실패: fileId={}, fileName={}, reason={}",
                        attachment.fileId(), attachment.fileName(), exception.getMessage());
            }
        }
        return List.of();
    }

    private List<LhAttachment> findAttachments(String html) {
        List<LhAttachment> attachments = new ArrayList<>();
        Matcher matcher = ATTACHMENT_PATTERN.matcher(html);
        while (matcher.find()) {
            attachments.add(new LhAttachment(matcher.group("id"), decodeHtml(matcher.group("name"))));
        }
        return List.copyOf(attachments);
    }

    private boolean isHousingListCandidate(LhAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        boolean supported = name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".pdf");
        boolean likelyList = name.contains("주택목록") || name.contains("공급주택")
                || name.contains("대상주택") || name.contains("주택 리스트");
        boolean excluded = name.contains("공고문") || name.contains("신청서")
                || name.contains("서식") || name.contains("도면");
        return supported && likelyList && !excluded;
    }

    private int attachmentPriority(LhAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        int score = name.contains("상세") ? 30 : 0;
        if (name.endsWith(".xlsx")) {
            return score + 300;
        }
        if (name.endsWith(".xls")) {
            return score + 200;
        }
        return score + 100;
    }

    private List<DetailedHousingUnit> parseAttachment(LhAttachment attachment, byte[] bytes) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return spreadsheetParser.parse(bytes, attachment);
        }
        if (name.endsWith(".pdf")) {
            return pdfParser.parse(bytes, attachment);
        }
        return List.of();
    }

    private String decodeHtml(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private List<Map<String, Object>> parseArray(String json) {
        try {
            return JSON_MAPPER.readValue(unescapeJavaScriptString(json), new TypeReference<>() {
            });
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private String unescapeJavaScriptString(String value) {
        return value.replace("\\'", "'")
                .replace("\\/", "/");
    }

    private void validateExpectedCount(List<Map<String, Object>> rows, List<DetailedHousingUnit> units) {
        Integer expected = rows.stream()
                .map(row -> integer(row.get("allCnt")))
                .filter(value -> value != null && value > 0)
                .findFirst()
                .orElse(null);
        if (expected != null && expected != units.size()) {
            throw new IllegalStateException("LH 상세 주택 수 불일치: expected=" + expected + ", parsed=" + units.size());
        }
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || !StringUtils.hasText(value.toString())) {
            return null;
        }
        return value.toString().trim();
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String firstAddressPart(String address, int index) {
        String[] parts = normalizeAddress(address).split(" ");
        return parts.length > index ? parts[index] : null;
    }

    private String normalizeAddress(String address) {
        return address.trim().replaceAll("\\s+", " ");
    }

    private Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(value.toString().replace(",", ""));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().replace(",", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
