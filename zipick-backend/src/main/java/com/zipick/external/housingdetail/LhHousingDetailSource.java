package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeAttachmentResponse;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
            "JSON\\.parse\\('(?<json>\\[.*?])'\\)",
            Pattern.DOTALL
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile(
            "fileDownLoad\\('(?<id>\\d+)'\\);?\\\">(?<name>[^<]+)</a>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PLAN_HINT = Pattern.compile(
            "도면|평면도|평면|floor\\s*plan|floorplan|drawing|blueprint",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern JSON_ASSET_KEY_HINT = Pattern.compile(
            "img|image|phto|photo|plan|floor|drawing|file|url|path|도면|평면",
            Pattern.CASE_INSENSITIVE
    );
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ExternalDocumentClient documentClient;
    private final LhSpreadsheetHousingParser spreadsheetParser;
    private final LhPdfHousingParser pdfParser;
    private final LhHwpHousingParser hwpParser;

    public LhHousingDetailSource(
            ExternalDocumentClient documentClient,
            LhSpreadsheetHousingParser spreadsheetParser,
            LhPdfHousingParser pdfParser,
            LhHwpHousingParser hwpParser
    ) {
        this.documentClient = documentClient;
        this.spreadsheetParser = spreadsheetParser;
        this.pdfParser = pdfParser;
        this.hwpParser = hwpParser;
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
    public HousingDetailResult extract(NoticeResponse notice) {
        URI detailUri = URI.create(notice.url());
        String html = documentClient.getText(detailUri);
        List<LhAttachment> attachments = findAttachments(html);
        List<DetailedHousingUnit> embeddedUnits = extractEmbeddedJson(html);
        List<DetailedHousingUnit> attachmentUnits = extractAttachments(detailUri, attachments);
        List<NoticeAttachmentResponse> planAssets = extractPlanAssets(detailUri, html, attachments);
        Map<String, List<DetailedHousingMedia>> mediaByUnitKey = associateMedia(
                embeddedUnits,
                extractHousingMedia(detailUri, html)
        );
        return new HousingDetailResult(
                mergeAttachmentDetails(embeddedUnits, attachmentUnits),
                planAssets,
                mediaByUnitKey
        );
    }

    private List<DetailedHousingMedia> extractHousingMedia(URI detailUri, String html) {
        Map<String, DetailedHousingMedia> media = new LinkedHashMap<>();
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(html);
        while (matcher.find()) {
            for (Map<String, Object> row : parseRows(matcher.group("json"))) {
                String complexKey = text(row, "sbdLgoNo");
                if (!StringUtils.hasText(complexKey)) {
                    continue;
                }
                String raw = JSON_MAPPER.writeValueAsString(row);
                String descriptor = String.join(" ", List.of(
                        nullableText(row, "lsSplInfUplFlDsCdNm"),
                        nullableText(row, "cmnAhflNm"),
                        nullableText(row, "lccNtFlNm"),
                        nullableText(row, "imgAhflDesc")
                ));
                String mediaType = classifyMediaType(descriptor);
                String fileId = text(row, "cmnAhflSn");
                if (mediaType != null && StringUtils.hasText(fileId)) {
                    addHousingMedia(media, detailUri, complexKey, fileId, mediaType,
                            firstText(text(row, "cmnAhflNm"), text(row, "lccNtFlNm")), raw);
                }

                String perspectiveId = text(row, "persCmnAhflSn");
                if (StringUtils.hasText(perspectiveId)) {
                    addHousingMedia(media, detailUri, complexKey, perspectiveId, "BUILDING_PHOTO",
                            firstText(text(row, "persCmnAhflNm"), "투시도-" + complexKey + ".jpg"), raw);
                }
            }
        }
        return List.copyOf(media.values());
    }

    private String nullableText(Map<String, Object> row, String key) {
        String value = text(row, key);
        return value == null ? "" : value;
    }

    private String classifyMediaType(String descriptor) {
        String normalized = descriptor.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(?:평면|floor\\s*plan|floorplan).*")) {
            return "FLOOR_PLAN";
        }
        if (normalized.matches(".*(?:단지배치|동호배치|배치도|site\\s*plan).*")) {
            return "SITE_PLAN";
        }
        if (normalized.matches(".*(?:투시|조감|외관|전경|주택사진|단지사진|perspective|exterior).*")) {
            return "BUILDING_PHOTO";
        }
        return null;
    }

    private void addHousingMedia(
            Map<String, DetailedHousingMedia> media,
            URI detailUri,
            String complexKey,
            String fileId,
            String mediaType,
            String fileName,
            String rawPayload
    ) {
        String sourceKey = "LH_JSON_MEDIA:" + fileId;
        String sourceUrl = detailUri.resolve("/lhapply/lhFile.do?fileid=" + fileId).toString();
        media.putIfAbsent(sourceKey, new DetailedHousingMedia(
                complexKey, sourceKey, mediaType, fileName, sourceUrl, rawPayload
        ));
    }

    private Map<String, List<DetailedHousingMedia>> associateMedia(
            List<DetailedHousingUnit> units,
            List<DetailedHousingMedia> media
    ) {
        Map<String, List<DetailedHousingMedia>> mediaByComplex = new LinkedHashMap<>();
        for (DetailedHousingMedia item : media) {
            mediaByComplex.computeIfAbsent(item.complexKey(), ignored -> new ArrayList<>()).add(item);
        }
        Map<String, List<DetailedHousingMedia>> result = new LinkedHashMap<>();
        for (DetailedHousingUnit unit : units) {
            String complexKey = jsonText(unit.rawPayload(), "sbdLgoNo");
            if (!StringUtils.hasText(complexKey)) {
                continue;
            }
            List<DetailedHousingMedia> matches = mediaByComplex.get(complexKey);
            if (matches != null && !matches.isEmpty()) {
                result.put(unit.sourceUnitKey(), List.copyOf(matches));
            }
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private String jsonText(String rawPayload, String key) {
        if (!StringUtils.hasText(rawPayload)) {
            return null;
        }
        try {
            Object parsed = JSON_MAPPER.readValue(rawPayload, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Object value = ((Map<String, Object>) map).get(key);
                return value == null ? null : value.toString().trim();
            }
        } catch (RuntimeException ignored) {
            // 원문 보존용 JSON이 깨져 있어도 주택 수집 자체는 계속한다.
        }
        return null;
    }

    private List<DetailedHousingUnit> extractEmbeddedJson(String html) {
        Map<String, DetailedHousingUnit> units = new LinkedHashMap<>();
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(html);
        while (matcher.find()) {
            for (Map<String, Object> row : parseRows(matcher.group("json"))) {
                String address = text(row, "adr");
                if (!StringUtils.hasText(address)) {
                    continue;
                }
                String unitNumber = text(row, "hoNo");
                String key = firstText(text(row, "hoAdmNo"), text(row, "ltrUntNo"));
                if (!StringUtils.hasText(key)) {
                    key = address + ":" + unitNumber;
                }
                DetailedHousingUnit unit = new DetailedHousingUnit(
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
                );
                units.putIfAbsent(unit.sourceUnitKey(), unit);
            }
        }
        return List.copyOf(units.values());
    }

    List<DetailedHousingUnit> mergeAttachmentDetails(
            List<DetailedHousingUnit> embedded,
            List<DetailedHousingUnit> attachments
    ) {
        List<DetailedHousingUnit> merged = new ArrayList<>(embedded);
        for (DetailedHousingUnit attachment : attachments) {
            if (!StringUtils.hasText(attachment.address())) {
                continue;
            }
            int matchingIndex = findMatchingUnitIndex(merged, attachment);
            if (matchingIndex < 0) {
                // 상세페이지 JSON이 이미 주택을 제공하는 경우 일반 공고문에서 정규식으로
                // 발견한 주소를 별도 주택으로 만들지 않는다. 구조화된 엑셀 또는 명시적인
                // 주택목록 문서만 JSON에 없는 주택을 추가할 수 있다.
                if (embedded.isEmpty() || isTrustedHousingListUnit(attachment)) {
                    merged.add(attachment);
                }
            } else {
                merged.set(matchingIndex, mergeAttachmentDetail(merged.get(matchingIndex), attachment));
            }
        }
        return merged.stream().map(this::removeInternalTrustMarker).toList();
    }

    private boolean isTrustedHousingListUnit(DetailedHousingUnit unit) {
        String key = unit.sourceUnitKey();
        return StringUtils.hasText(key)
                && (key.startsWith("LH_FILE:")
                || key.startsWith("LH_PDF_LIST:")
                || key.startsWith("LH_HWP_LIST:"));
    }

    private DetailedHousingUnit removeInternalTrustMarker(DetailedHousingUnit unit) {
        String key = unit.sourceUnitKey();
        String normalizedKey = key == null ? null : key
                .replaceFirst("^LH_PDF_LIST:", "LH_PDF:")
                .replaceFirst("^LH_HWP_LIST:", "LH_HWP:");
        if (java.util.Objects.equals(key, normalizedKey)) {
            return unit;
        }
        return new DetailedHousingUnit(
                normalizedKey,
                unit.complexName(),
                unit.provinceName(),
                unit.districtName(),
                unit.address(),
                unit.buildingDong(),
                unit.unitNumber(),
                unit.floor(),
                unit.exclusiveArea(),
                unit.roomCount(),
                unit.housingType(),
                unit.supplyType(),
                unit.rentDeposit(),
                unit.monthlyRent(),
                unit.rawPayload()
        );
    }

    private int findMatchingUnitIndex(List<DetailedHousingUnit> existing, DetailedHousingUnit candidate) {
        String candidateKey = detailKey(candidate);
        for (int index = 0; index < existing.size(); index++) {
            if (candidateKey.equals(detailKey(existing.get(index)))) {
                return index;
            }
        }

        String addressKey = AddressNormalizer.canonicalize(candidate.address());
        List<Integer> sameAddress = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            if (addressKey.equals(AddressNormalizer.canonicalize(existing.get(index).address()))) {
                sameAddress.add(index);
            }
        }
        if (sameAddress.size() == 1) {
            DetailedHousingUnit only = existing.get(sameAddress.getFirst());
            if (!StringUtils.hasText(only.unitNumber()) || !StringUtils.hasText(candidate.unitNumber())) {
                return sameAddress.getFirst();
            }
        }
        return -1;
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
                mergeRawPayload(embedded.rawPayload(), attachment.rawPayload())
        );
    }

    private String mergeRawPayload(String primary, String supplement) {
        if (!StringUtils.hasText(primary)) {
            return supplement;
        }
        if (!StringUtils.hasText(supplement) || primary.equals(supplement)) {
            return primary;
        }
        return "{\"primary\":" + primary + ",\"supplement\":" + supplement + "}";
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

    private List<DetailedHousingUnit> extractAttachments(URI detailUri, List<LhAttachment> attachments) {
        List<DetailedHousingUnit> extracted = new ArrayList<>();
        List<LhAttachment> supported = attachments.stream()
                .filter(this::isSupportedDocument)
                .toList();
        for (List<LhAttachment> sameDocument : groupByDocumentName(supported)) {
            for (LhAttachment attachment : sameDocument) {
                try {
                    URI downloadUri = detailUri.resolve("/lhapply/lhFile.do?fileid=" + attachment.fileId());
                    byte[] bytes = documentClient.getBytes(downloadUri);
                    List<DetailedHousingUnit> units = parseAttachment(attachment, bytes);
                    if (!units.isEmpty()) {
                        extracted.addAll(units);
                        log.info("LH 첨부 주택목록 선택: fileId={}, fileName={}, units={}",
                                attachment.fileId(), attachment.fileName(), units.size());
                        break;
                    }
                } catch (RuntimeException exception) {
                    log.warn("LH 첨부 주택목록 파싱 실패: fileId={}, fileName={}, reason={}",
                            attachment.fileId(), attachment.fileName(), exception.getMessage());
                }
            }
        }
        return List.copyOf(extracted);
    }

    private List<NoticeAttachmentResponse> extractPlanAssets(
            URI detailUri,
            String html,
            List<LhAttachment> attachments
    ) {
        Map<String, NoticeAttachmentResponse> assets = new LinkedHashMap<>();
        for (LhAttachment attachment : attachments) {
            if (!PLAN_HINT.matcher(attachment.fileName()).find()) {
                continue;
            }
            String downloadUrl = detailUri.resolve(
                    "/lhapply/lhFile.do?fileid=" + attachment.fileId()
            ).toString();
            NoticeAttachmentResponse asset = new NoticeAttachmentResponse(
                    "LH_FILE:" + attachment.fileId(),
                    "FLOOR_PLAN",
                    attachment.fileName(),
                    downloadUrl,
                    null
            );
            assets.putIfAbsent(asset.sourceKey(), asset);
        }

        Matcher matcher = JSON_ARRAY_PATTERN.matcher(html);
        while (matcher.find()) {
            for (Map<String, Object> row : parseRows(matcher.group("json"))) {
                collectJsonPlanAsset(detailUri, row, assets);
            }
        }
        return List.copyOf(assets.values());
    }

    private void collectJsonPlanAsset(
            URI detailUri,
            Map<String, Object> row,
            Map<String, NoticeAttachmentResponse> assets
    ) {
        String raw = JSON_MAPPER.writeValueAsString(row);
        boolean rowHasPlanHint = PLAN_HINT.matcher(raw).find();
        String fileId = text(row, "cmnAhflSn");
        if (rowHasPlanHint && StringUtils.hasText(fileId)) {
            String sourceUrl = detailUri.resolve("/lhapply/lhFile.do?fileid=" + fileId).toString();
            String sourceKey = "LH_JSON_PLAN_FILE:" + fileId;
            assets.putIfAbsent(sourceKey, new NoticeAttachmentResponse(
                    sourceKey,
                    "FLOOR_PLAN",
                    firstText(text(row, "cmnAhflNm"), text(row, "lccNtFlNm")),
                    sourceUrl,
                    raw
            ));
            return;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String value = entry.getValue().toString().trim();
            boolean hinted = rowHasPlanHint || PLAN_HINT.matcher(entry.getKey()).find()
                    || (JSON_ASSET_KEY_HINT.matcher(entry.getKey()).find()
                    && PLAN_HINT.matcher(value).find());
            if (!hinted || !looksLikeAssetLocation(value)) {
                continue;
            }
            String sourceUrl = resolveAssetUrl(detailUri, value);
            String sourceKey = "LH_JSON_PLAN:" + shortHash(sourceUrl);
            assets.putIfAbsent(sourceKey, new NoticeAttachmentResponse(
                    sourceKey,
                    "FLOOR_PLAN",
                    fileNameFrom(value),
                    sourceUrl,
                    raw
            ));
        }
    }

    private boolean looksLikeAssetLocation(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/")
                || lower.matches(".*\\.(?:pdf|hwp|hwpx|png|jpe?g|gif|webp)(?:\\?.*)?");
    }

    private String resolveAssetUrl(URI detailUri, String value) {
        try {
            return detailUri.resolve(value).toString();
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private String fileNameFrom(String value) {
        String withoutQuery = value.replaceFirst("[?#].*$", "");
        int slash = Math.max(withoutQuery.lastIndexOf('/'), withoutQuery.lastIndexOf('\\'));
        return slash >= 0 ? withoutQuery.substring(slash + 1) : withoutQuery;
    }

    List<List<LhAttachment>> groupByDocumentName(List<LhAttachment> attachments) {
        Map<String, List<LhAttachment>> grouped = new LinkedHashMap<>();
        for (LhAttachment attachment : attachments) {
            grouped.computeIfAbsent(documentNameKey(attachment.fileName()), ignored -> new ArrayList<>())
                    .add(attachment);
        }
        return grouped.values().stream()
                .map(group -> group.stream()
                        .sorted(Comparator.comparingInt(this::attachmentPriority).reversed())
                        .toList())
                .sorted(Comparator.comparingInt((List<LhAttachment> group) ->
                        group.stream().mapToInt(this::attachmentPriority).max().orElse(0)).reversed())
                .toList();
    }

    private String documentNameKey(String fileName) {
        return fileName.toLowerCase(Locale.ROOT)
                .replaceFirst("\\.(?:xlsx|xls|pdf|hwp|hwpx)$", "")
                .replaceAll("[\\s._-]", "");
    }

    private List<LhAttachment> findAttachments(String html) {
        List<LhAttachment> attachments = new ArrayList<>();
        Matcher matcher = ATTACHMENT_PATTERN.matcher(html);
        while (matcher.find()) {
            attachments.add(new LhAttachment(matcher.group("id"), decodeHtml(matcher.group("name"))));
        }
        return List.copyOf(attachments);
    }

    private boolean isSupportedDocument(LhAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".pdf") || name.endsWith(".hwp") || name.endsWith(".hwpx");
    }

    private int attachmentPriority(LhAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        int score = name.contains("상세") ? 30 : 0;
        if (name.endsWith(".xlsx")) {
            return score + 300;
        }
        if (name.endsWith(".xls")) {
            return score + 250;
        }
        if (name.endsWith(".pdf")) {
            return score + 200;
        }
        return score + 100;
    }

    private List<DetailedHousingUnit> parseAttachment(LhAttachment attachment, byte[] bytes) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return spreadsheetParser.parse(bytes, attachment);
        }
        boolean housingList = isHousingListDocument(name);
        if (name.endsWith(".pdf")) {
            return pdfParser.parse(bytes, attachment, housingList ? "LH_PDF_LIST" : "LH_PDF");
        }
        if (name.endsWith(".hwp") || name.endsWith(".hwpx")) {
            return hwpParser.parse(bytes, attachment, housingList ? "LH_HWP_LIST" : "LH_HWP");
        }
        return List.of();
    }

    private boolean isHousingListDocument(String fileName) {
        String normalized = fileName.replaceAll("[\\s._-]", "");
        return normalized.matches(".*(?:주택(?:목록|리스트|명세|내역)|(?:목록|리스트|명세|내역).*주택).*" );
    }

    private String decodeHtml(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private List<Map<String, Object>> parseRows(String json) {
        try {
            Object value = JSON_MAPPER.readValue(unescapeJavaScriptString(json), Object.class);
            List<Map<String, Object>> rows = new ArrayList<>();
            collectRows(value, rows);
            return List.copyOf(rows);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void collectRows(Object value, List<Map<String, Object>> rows) {
        if (value instanceof Map<?, ?> map) {
            rows.add((Map<String, Object>) map);
            for (Object child : map.values()) {
                if (child instanceof List<?> || child instanceof Map<?, ?>) {
                    collectRows(child, rows);
                }
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                collectRows(child, rows);
            }
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
