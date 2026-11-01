package com.zipick.external.housingdetail;

import com.zipick.external.ai.GeminiPdfHousingDetailSupport;
import com.zipick.external.sh.ShPdfHousingParser;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.support.AddressNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
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
            "<a[^>]+(?:href|onclick)\\s*=\\s*[\\\"](?:javascript:)?fileDownLoad\\('(?<id>\\d+)'\\);?[\\\"][^>]*>"
                    + "(?<name>.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
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
    private final ShPdfHousingParser shPdfParser;
    private GeminiPdfHousingDetailSupport geminiSupport;
    private com.zipick.notice.repository.DocumentParseHistoryRepository historyRepository;

    @Autowired(required = false)
    void setHistoryRepository(com.zipick.notice.repository.DocumentParseHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public LhHousingDetailSource(
            ExternalDocumentClient documentClient,
            LhSpreadsheetHousingParser spreadsheetParser,
            LhPdfHousingParser pdfParser,
            LhHwpHousingParser hwpParser,
            ShPdfHousingParser shPdfParser
    ) {
        this.documentClient = documentClient;
        this.spreadsheetParser = spreadsheetParser;
        this.pdfParser = pdfParser;
        this.hwpParser = hwpParser;
        this.shPdfParser = shPdfParser;
    }

    @Autowired(required = false)
    void setGeminiSupport(GeminiPdfHousingDetailSupport geminiSupport) {
        this.geminiSupport = geminiSupport;
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
        AttachmentExtraction extracted = extractAttachments(notice, detailUri, attachments);
        List<DetailedHousingUnit> attachmentUnits = extracted.units();
        boolean ambiguousUnitsExcluded = false;
        if ((geminiSupport == null || !geminiSupport.enabled()) && !notice.houses().isEmpty() && embeddedUnits.isEmpty()) {
            int countBefore = attachmentUnits.size();
            attachmentUnits = attachmentUnits.stream()
                    .filter(unit -> isTrustedHousingListUnit(unit)
                            || notice.houses().stream().anyMatch(housing ->
                                    java.util.Objects.equals(AddressNormalizer.canonicalize(housing.fullAddress()),
                                            AddressNormalizer.canonicalize(unit.address())))
                            || (unit.rawPayload() != null && unit.rawPayload().matches(".*(?:건설위치|공급위치|주택단지\\s*위치|소재지).*")))
                    .toList();
            ambiguousUnitsExcluded = attachmentUnits.size() < countBefore;
            if (ambiguousUnitsExcluded) {
                log.warn("출처가 불명확한 공고문 주소를 제외하고 다음 실행에서 재확인합니다: noticeId={}", notice.noticeId());
            }
        }
        List<NoticeAttachmentResponse> planAssets = mergeAttachments(
                extractAttachmentMetadata(detailUri, attachments),
                extractPlanAssets(detailUri, html, attachments)
        );
        planAssets = preserveDocumentAttributes(planAssets, extracted.documentAttributes());
        Map<String, List<DetailedHousingMedia>> mediaByUnitKey = associateMedia(
                embeddedUnits,
                extractHousingMedia(detailUri, html)
        );
        return new HousingDetailResult(
                mergeAttachmentDetails(embeddedUnits, attachmentUnits),
                planAssets,
                mediaByUnitKey,
                extracted.skipped() || ambiguousUnitsExcluded,
                ambiguousUnitsExcluded ? List.of() : extracted.checkpoints()
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
        Map<String, java.util.Set<Integer>> matchedAiRows = new LinkedHashMap<>();
        for (DetailedHousingUnit attachment : attachments) {
            if (!StringUtils.hasText(attachment.address())) {
                continue;
            }
            int matchingIndex = findMatchingUnitIndex(merged, attachment);
            String aiDocument = aiDocumentKey(attachment.sourceUnitKey());
            if (aiDocument != null && matchingIndex >= 0) {
                String matchedKey = merged.get(matchingIndex).sourceUnitKey();
                boolean differentAiRow = aiDocument.equals(aiDocumentKey(matchedKey))
                        && !java.util.Objects.equals(matchedKey, attachment.sourceUnitKey());
                if (differentAiRow || matchedAiRows.getOrDefault(aiDocument, java.util.Set.of()).contains(matchingIndex)) {
                    matchingIndex = -1;
                }
            }
            if (matchingIndex < 0) {
                // 상세페이지 JSON이 이미 주택을 제공하는 경우 일반 공고문에서 정규식으로
                // 발견한 주소를 별도 주택으로 만들지 않는다. 구조화된 엑셀 또는 명시적인
                // 주택목록 문서만 JSON에 없는 주택을 추가할 수 있다.
                if (embedded.isEmpty() || isTrustedHousingListUnit(attachment)) {
                    merged.add(attachment);
                    if (aiDocument != null) matchedAiRows.computeIfAbsent(aiDocument, ignored -> new java.util.HashSet<>()).add(merged.size() - 1);
                }
            } else {
                merged.set(matchingIndex, mergeAttachmentDetail(merged.get(matchingIndex), attachment));
                if (aiDocument != null) matchedAiRows.computeIfAbsent(aiDocument, ignored -> new java.util.HashSet<>()).add(matchingIndex);
            }
        }
        return merged.stream().map(this::removeInternalTrustMarker).toList();
    }

    private String aiDocumentKey(String key) {
        if (!StringUtils.hasText(key) || !key.startsWith("GEMINI:")) return null;
        int separator = key.lastIndexOf('/');
        return separator < 0 ? key : key.substring(0, separator);
    }

    private boolean isTrustedHousingListUnit(DetailedHousingUnit unit) {
        String key = unit.sourceUnitKey();
        return StringUtils.hasText(key)
                && (key.startsWith("LH_FILE:")
                || key.startsWith("LH_PDF_LIST:")
                  || key.startsWith("LH_HWP_LIST:")
                  || key.startsWith("GEMINI:"));
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

        // 건물명이나 괄호 뒤의 부가 설명이 달라도 도로명·건물번호와 호수가 같으면 같은 행입니다.
        String road = roadAddressKey(candidate.address());
        String unit = normalizeUnitNumber(candidate.unitNumber());
        if (road != null && !unit.isEmpty()) {
            List<Integer> matches = new ArrayList<>();
            for (int index = 0; index < existing.size(); index++) {
                DetailedHousingUnit current = existing.get(index);
                if (road.equals(roadAddressKey(current.address()))
                        && unit.equals(normalizeUnitNumber(current.unitNumber()))
                        && compatibleBuildingDong(current.buildingDong(), candidate.buildingDong())) {
                    matches.add(index);
                }
            }
            if (matches.size() == 1) return matches.getFirst();
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

    private String roadAddressKey(String address) {
        if (!StringUtils.hasText(address)) return null;
        Matcher matcher = Pattern.compile("^(.+?(?:대로|로|길)(?:\\s*\\d+번길)?\\s+\\d+(?:-\\d+)?)")
                .matcher(address.trim());
        return matcher.find() ? AddressNormalizer.canonicalize(matcher.group(1)) : null;
    }

    private boolean compatibleBuildingDong(String first, String second) {
        if (!StringUtils.hasText(first) || !StringUtils.hasText(second)) return true;
        return first.replaceAll("[\\s동]", "").equalsIgnoreCase(second.replaceAll("[\\s동]", ""));
    }

    private DetailedHousingUnit mergeAttachmentDetail(
            DetailedHousingUnit embedded,
            DetailedHousingUnit attachment
    ) {
        return mergeAttachmentDetail(embedded, attachment, false);
    }

    private DetailedHousingUnit mergeAttachmentDetail(
            DetailedHousingUnit embedded, DetailedHousingUnit attachment, boolean preferPrimaryConditions
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
                  mergeRawPayload(embedded.rawPayload(), attachment.rawPayload()),
                  firstText(embedded.lotAddress(), attachment.lotAddress()),
                  firstText(embedded.roadAddress(), attachment.roadAddress()),
                  firstValue(embedded.sourceRowNumber(), attachment.sourceRowNumber()),
                  firstText(embedded.supplyStatus(), attachment.supplyStatus()),
                  firstText(embedded.nearbySubwayStation(), attachment.nearbySubwayStation()),
                  firstValue(embedded.subwayWalkDistanceKm(), attachment.subwayWalkDistanceKm()),
                  firstValue(embedded.elevatorAvailable(), attachment.elevatorAvailable()),
                  firstValue(embedded.communityFacilityAvailable(), attachment.communityFacilityAvailable()),
                  firstValue(embedded.totalParkingSpaces(), attachment.totalParkingSpaces()),
                  firstValue(embedded.mechanicalParkingAvailable(), attachment.mechanicalParkingAvailable()),
                  firstValue(embedded.mechanicalParkingSpaces(), attachment.mechanicalParkingSpaces()),
                  firstValue(embedded.supplyCount(), attachment.supplyCount()),
                  preferPrimaryConditions && !embedded.rentalConditions().isEmpty() ? embedded.rentalConditions()
                          : !attachment.rentalConditions().isEmpty() ? attachment.rentalConditions() : embedded.rentalConditions()
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

    private record AttachmentExtraction(List<DetailedHousingUnit> units, boolean skipped,
            List<com.zipick.notice.dto.ParseCheckpoint> checkpoints,
            Map<String, List<Map<String, Object>>> documentAttributes) { }
    private record ParsedAttachment(List<DetailedHousingUnit> units, boolean aiComplete, List<Map<String, Object>> attributes) {
        ParsedAttachment(List<DetailedHousingUnit> units, boolean aiComplete) { this(units, aiComplete, List.of()); }
    }

    private AttachmentExtraction extractAttachments(
            NoticeResponse notice,
            URI detailUri,
            List<LhAttachment> attachments
    ) {
        List<DetailedHousingUnit> extracted = new ArrayList<>();
        List<com.zipick.notice.dto.ParseCheckpoint> checkpoints = new ArrayList<>();
        Map<String, List<Map<String, Object>>> documentAttributes = new LinkedHashMap<>();
        boolean skipped = false;
        List<LhAttachment> supported = attachments.stream()
                .filter(this::isSupportedDocument)
                .toList();
        for (List<LhAttachment> sameDocument : groupByDocumentName(supported)) {
            for (LhAttachment attachment : sameDocument) {
                try {
                    URI downloadUri = detailUri.resolve("/lhapply/lhFile.do?fileid=" + attachment.fileId());
                    byte[] bytes = documentClient.getBytes(downloadUri);
                    String key = "LH:" + shortHash(notice.noticeId() + ":" + attachment.fileId()) + ":"
                            + DocumentFingerprint.sha256(bytes);
                    boolean aiEnabled = geminiSupport != null && geminiSupport.enabled();
                    boolean pdf = attachment.fileName().toLowerCase(Locale.ROOT).endsWith(".pdf");
                    boolean cacheEnabled = historyRepository != null;
                    boolean spreadsheet = attachment.fileName().toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xls)$");
                    String parserVersion = aiEnabled && pdf ? "LH_" + geminiSupport.parserVersion()
                            : spreadsheet ? "LH_SPREADSHEET_V3" : "LH_DOCUMENT_V4";
                    if (!(aiEnabled && com.zipick.external.ai.AiReprocessingScope.includes(notice.noticeId()))
                            && cacheEnabled && historyRepository.findByDocumentKeyAndParserVersion(key, parserVersion)
                            .filter(com.zipick.notice.domain.DocumentParseHistory::isSuccess).isPresent()) {
                        skipped = true;
                        log.info("LH 변경 없는 첨부 재파싱 생략: fileId={}", attachment.fileId());
                        break;
                    }
                    ParsedAttachment parsed = parseAttachment(notice, attachment, bytes);
                    List<DetailedHousingUnit> units = parsed.units();
                    if (!parsed.attributes().isEmpty()) documentAttributes.put("LH_FILE:" + attachment.fileId(), parsed.attributes());
                    if (units.isEmpty() && isPamphlet(attachment.fileName()) && !extracted.isEmpty()) {
                        preservePamphletAttributes(extracted, parsed.attributes());
                    }
                    if (!units.isEmpty()) {
                        if (isPamphlet(attachment.fileName())) mergePamphletUnits(extracted, units);
                        else extracted.addAll(units);
                        if (cacheEnabled && (!aiEnabled || !pdf || parsed.aiComplete())) {
                            checkpoints.add(new com.zipick.notice.dto.ParseCheckpoint(key,
                                    parserVersion, bytes.length, units.size()));
                        }
                        log.info("LH 첨부 주택목록 선택: fileId={}, fileName={}, units={}",
                                attachment.fileId(), attachment.fileName(), units.size());
                        break;
                    }
                } catch (RuntimeException exception) {
                    skipped = true;
                    log.warn("LH 첨부 주택목록 파싱 실패: fileId={}, fileName={}, reason={}",
                            attachment.fileId(), attachment.fileName(), exception.getMessage());
                }
            }
        }
        return new AttachmentExtraction(List.copyOf(extracted), skipped, List.copyOf(checkpoints), Map.copyOf(documentAttributes));
    }

    List<NoticeAttachmentResponse> preserveDocumentAttributes(List<NoticeAttachmentResponse> attachments,
            Map<String, List<Map<String, Object>>> attributes) {
        return attachments.stream().map(attachment -> {
            var values = attributes.get(attachment.sourceKey());
            if (values == null || values.isEmpty()) return attachment;
            Map<String, Object> payload = new LinkedHashMap<>();
            if (StringUtils.hasText(attachment.rawPayload())) {
                payload.put("previousPayload", JSON_MAPPER.readTree(attachment.rawPayload()));
            }
            payload.put("documentAdditionalAttributes", values);
            String raw = JSON_MAPPER.writeValueAsString(payload);
            return new NoticeAttachmentResponse(attachment.sourceKey(), attachment.attachmentType(),
                    attachment.fileName(), attachment.sourceUrl(), raw);
        }).toList();
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

    private List<NoticeAttachmentResponse> extractAttachmentMetadata(
            URI detailUri,
            List<LhAttachment> attachments
    ) {
        return attachments.stream().map(attachment -> new NoticeAttachmentResponse(
                "LH_FILE:" + attachment.fileId(),
                attachmentType(attachment.fileName()),
                attachment.fileName(),
                detailUri.resolve("/lhapply/lhFile.do?fileid=" + attachment.fileId()).toString(),
                null
        )).toList();
    }

    private List<NoticeAttachmentResponse> mergeAttachments(
            List<NoticeAttachmentResponse> primary,
            List<NoticeAttachmentResponse> supplement
    ) {
        Map<String, NoticeAttachmentResponse> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.sourceKey(), item));
        supplement.forEach(item -> merged.putIfAbsent(item.sourceKey(), item));
        return List.copyOf(merged.values());
    }

    private String attachmentType(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (PLAN_HINT.matcher(name).find()) return "FLOOR_PLAN";
        if (isHousingListDocument(name)) return "HOUSING_LIST";
        if (name.contains("공고")) return "ANNOUNCEMENT";
        if (name.contains("신청") || name.contains("서식") || name.contains("양식")) return "APPLICATION_FORM";
        return "OTHER";
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
            String name = org.jsoup.Jsoup.parseBodyFragment(matcher.group("name")).text();
            attachments.add(new LhAttachment(matcher.group("id"), decodeHtml(name)));
        }
        return List.copyOf(attachments);
    }

    private boolean isSupportedDocument(LhAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        if (isConsentFormOnly(name)) return false;
        return name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".pdf") || name.endsWith(".hwp") || name.endsWith(".hwpx");
    }

    static boolean isConsentFormOnly(String name) {
        if (name == null) return false;
        String compact = name.replaceAll("\\s+", "");
        return compact.contains("동의서") && !compact.contains("공고")
                && !compact.contains("주택목록") && !compact.contains("공급목록");
    }

    private int attachmentPriority(LhAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        int score = name.contains("상세") ? 30 : 0;
        if (isPamphlet(name)) score -= 20;
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

    private boolean isPamphlet(String fileName) {
        return fileName != null && fileName.matches(".*(?:팸플릿|팜플렛|팸플렛|리플릿|리플렛).*");
    }

    void preservePamphletAttributes(List<DetailedHousingUnit> units, List<Map<String, Object>> attributes) {
        if (units.isEmpty() || attributes == null || attributes.isEmpty()) return;
        var documentAttributes = attributes.stream().filter(value -> "DOCUMENT".equals(value.get("scope"))).toList();
        if (documentAttributes.isEmpty()) return;
        DetailedHousingUnit u = units.getFirst();
        String supplement = JSON_MAPPER.writeValueAsString(Map.of("documentAdditionalAttributes", documentAttributes));
        units.set(0, new DetailedHousingUnit(u.sourceUnitKey(), u.complexName(), u.provinceName(), u.districtName(),
                u.address(), u.buildingDong(), u.unitNumber(), u.floor(), u.exclusiveArea(), u.roomCount(),
                u.housingType(), u.supplyType(), u.rentDeposit(), u.monthlyRent(), mergeRawPayload(u.rawPayload(), supplement),
                u.lotAddress(), u.roadAddress(), u.sourceRowNumber(), u.supplyStatus(), u.nearbySubwayStation(),
                u.subwayWalkDistanceKm(), u.elevatorAvailable(), u.communityFacilityAvailable(), u.totalParkingSpaces(),
                u.mechanicalParkingAvailable(), u.mechanicalParkingSpaces(), u.supplyCount(), u.rentalConditions()));
    }

    void mergePamphletUnits(List<DetailedHousingUnit> existing, List<DetailedHousingUnit> pamphlet) {
        for (DetailedHousingUnit unit : pamphlet) {
            var matches = java.util.stream.IntStream.range(0, existing.size()).filter(index -> {
                DetailedHousingUnit primary = existing.get(index);
                return StringUtils.hasText(unit.housingType()) && unit.housingType().equalsIgnoreCase(primary.housingType())
                        && sameComplex(primary.complexName(), unit.complexName());
            }).boxed().toList();
            if (matches.size() == 1) {
                int index = matches.getFirst();
                existing.set(index, mergeAttachmentDetail(existing.get(index), unit, true));
            } else existing.add(unit);
        }
    }

    private boolean sameComplex(String first, String second) {
        if (!StringUtils.hasText(first) || !StringUtils.hasText(second)) return false;
        String left = first.toLowerCase(Locale.ROOT).replace("블록", "").replace("bl", "").replaceAll("[^가-힣a-z0-9]", "");
        String right = second.toLowerCase(Locale.ROOT).replace("블록", "").replace("bl", "").replaceAll("[^가-힣a-z0-9]", "");
        return left.length() >= 6 && right.length() >= 6 && (left.contains(right) || right.contains(left));
    }

    private ParsedAttachment parseAttachment(
            NoticeResponse notice,
            LhAttachment attachment,
            byte[] bytes
    ) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return new ParsedAttachment(spreadsheetParser.parse(bytes, attachment), true);
        }
        boolean housingList = isHousingListDocument(name);
        if (name.endsWith(".pdf")) {
            List<DetailedHousingUnit> fallback = com.zipick.external.ai.GeminiPdfStageTiming.measureBasicParser(() -> {
                List<DetailedHousingUnit> structured = shPdfParser.parse(
                        bytes, attachment, housingList ? "LH_PDF_LIST" : "LH_PDF");
                return !structured.isEmpty() ? structured
                        : pdfParser.parse(bytes, attachment, housingList ? "LH_PDF_LIST" : "LH_PDF");
            });
            if (geminiSupport == null || !geminiSupport.enabled()) return new ParsedAttachment(fallback, true);
            var result = geminiSupport.supplementWithStatus(
                    bytes, "LH_FILE:" + attachment.fileId(), "LH", notice.title(), attachment.fileName(), fallback);
            return new ParsedAttachment(result.units(), result.aiApplied() && result.aiComplete(), result.additionalAttributes());
        }
        if (name.endsWith(".hwp") || name.endsWith(".hwpx")) {
            return new ParsedAttachment(hwpParser.parse(bytes, attachment, housingList ? "LH_HWP_LIST" : "LH_HWP"), true);
        }
        return new ParsedAttachment(List.of(), true);
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
