package com.zipick.external.sh;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailResult;
import com.zipick.external.housingdetail.HousingDetailSource;
import com.zipick.external.housingdetail.LhAttachment;
import com.zipick.external.housingdetail.LhHwpHousingParser;
import com.zipick.external.housingdetail.LhPdfHousingParser;
import com.zipick.external.housingdetail.LhSpreadsheetHousingParser;
import com.zipick.notice.domain.DocumentParseHistory;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.repository.DocumentParseHistoryRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(20)
public class ShHousingDetailSource implements HousingDetailSource {

    private static final Logger log = LoggerFactory.getLogger(ShHousingDetailSource.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String PARSER_VERSION = "SH_DOCUMENT_V1";
    private static final String MEDIA_LINK_PARSER_VERSION = "SH_MEDIA_LINK_V1";
    private static final Pattern ATTACHMENTS = Pattern.compile(
            "initParam\\.downList\\s*=\\s*(?<json>\\[.*?])\\s*;", Pattern.DOTALL
    );
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);

    private final ExternalDocumentClient documentClient;
    private final ShPdfHousingParser shPdfParser;
    private final LhSpreadsheetHousingParser spreadsheetParser;
    private final LhPdfHousingParser fallbackPdfParser;
    private final LhHwpHousingParser hwpParser;
    private final DocumentParseHistoryRepository historyRepository;

    public ShHousingDetailSource(
            ExternalDocumentClient documentClient,
            ShPdfHousingParser shPdfParser,
            LhSpreadsheetHousingParser spreadsheetParser,
            LhPdfHousingParser fallbackPdfParser,
            LhHwpHousingParser hwpParser,
            DocumentParseHistoryRepository historyRepository
    ) {
        this.documentClient = documentClient;
        this.shPdfParser = shPdfParser;
        this.spreadsheetParser = spreadsheetParser;
        this.fallbackPdfParser = fallbackPdfParser;
        this.hwpParser = hwpParser;
        this.historyRepository = historyRepository;
    }

    @Override
    public boolean supports(NoticeResponse notice) {
        if ("SH".equalsIgnoreCase(notice.source())) {
            return true;
        }
        if (!StringUtils.hasText(notice.url())) {
            return false;
        }
        URI uri = URI.create(notice.url());
        return uri.getHost() != null && uri.getHost().toLowerCase(Locale.ROOT).endsWith("i-sh.co.kr");
    }

    @Override
    public HousingDetailResult extract(NoticeResponse notice) {
        URI detailUri = URI.create(notice.url());
        String html = documentClient.getText(detailUri);
        List<ShAttachment> attachments = findAttachments(html);
        List<NoticeAttachmentResponse> savedAttachments = toAttachmentResponses(detailUri, attachments);
        savedAttachments = appendExternalMediaLinks(savedAttachments, detailUri, attachments);

        for (ShAttachment attachment : housingCandidates(attachments)) {
            String documentKey = documentKey(attachment);
            if (historyRepository.findByDocumentKeyAndParserVersion(documentKey, PARSER_VERSION)
                    .filter(DocumentParseHistory::isSuccess)
                    .isPresent()) {
                log.info("SH 첨부 재파싱 생략: seq={}, fileSeq={}, fileName={}",
                        attachment.seq(), attachment.fileSeq(), attachment.oriFileNm());
                return new HousingDetailResult(List.of(), savedAttachments, Map.of());
            }
            try {
                byte[] bytes = documentClient.getBytes(downloadUri(detailUri, attachment));
                List<DetailedHousingUnit> units = parseAttachment(attachment, bytes);
                if (units.isEmpty()) {
                    continue;
                }
                DocumentParseHistory history = historyRepository
                        .findByDocumentKeyAndParserVersion(documentKey, PARSER_VERSION)
                        .orElseGet(() -> new DocumentParseHistory(documentKey, PARSER_VERSION));
                history.markSuccess(attachment.fileSize(), units.size());
                historyRepository.save(history);
                log.info("SH 공급주택 첨부 선택: seq={}, fileSeq={}, fileName={}, units={}",
                        attachment.seq(), attachment.fileSeq(), attachment.oriFileNm(), units.size());
                return new HousingDetailResult(units, savedAttachments, Map.of());
            } catch (RuntimeException exception) {
                log.warn("SH 첨부 파싱 실패: seq={}, fileSeq={}, fileName={}, reason={}",
                        attachment.seq(), attachment.fileSeq(), attachment.oriFileNm(), exception.getMessage());
            }
        }
        return new HousingDetailResult(List.of(), savedAttachments, Map.of());
    }

    List<ShAttachment> findAttachments(String html) {
        Matcher matcher = ATTACHMENTS.matcher(html);
        if (!matcher.find()) {
            return List.of();
        }
        try {
            String json = matcher.group("json").replace("\r", "").replace("\n", "");
            List<Map<String, Object>> rows = JSON_MAPPER.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() { }
            );
            List<ShAttachment> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(new ShAttachment(
                        text(row, "brdId"), text(row, "seq"), text(row, "fileSeq"),
                        longValue(row.get("fileSize")), text(row, "oriFileNm"), text(row, "fileTp")
                ));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("SH 첨부파일 목록을 읽지 못했습니다.", exception);
        }
    }

    List<ShAttachment> housingCandidates(List<ShAttachment> attachments) {
        return attachments.stream()
                .filter(this::isHousingDocument)
                .sorted(Comparator.comparingInt(this::priority).reversed())
                .toList();
    }

    private List<DetailedHousingUnit> parseAttachment(ShAttachment attachment, byte[] bytes) {
        String name = normalizedName(attachment.oriFileNm());
        LhAttachment compatible = new LhAttachment(attachment.fileSeq(), attachment.oriFileNm());
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return spreadsheetParser.parse(bytes, compatible);
        }
        if (name.endsWith(".pdf")) {
            List<DetailedHousingUnit> structured = shPdfParser.parse(bytes, attachment);
            return structured.isEmpty() ? fallbackPdfParser.parse(bytes, compatible) : structured;
        }
        if (name.endsWith(".hwp") || name.endsWith(".hwpx")) {
            return hwpParser.parse(bytes, compatible);
        }
        return List.of();
    }

    private List<NoticeAttachmentResponse> toAttachmentResponses(
            URI detailUri,
            List<ShAttachment> attachments
    ) {
        List<NoticeAttachmentResponse> result = new ArrayList<>();
        for (ShAttachment attachment : attachments) {
            result.add(new NoticeAttachmentResponse(
                    "SH_FILE:" + attachment.seq() + ":" + attachment.fileSeq(),
                    attachmentType(attachment.oriFileNm()), attachment.oriFileNm(),
                    downloadUri(detailUri, attachment).toString(),
                    JSON_MAPPER.writeValueAsString(Map.of(
                            "fileSize", attachment.fileSize() == null ? 0L : attachment.fileSize(),
                            "fileSeq", attachment.fileSeq()
                    ))
            ));
        }
        return List.copyOf(result);
    }

    private List<NoticeAttachmentResponse> appendExternalMediaLinks(
            List<NoticeAttachmentResponse> base,
            URI detailUri,
            List<ShAttachment> attachments
    ) {
        Map<String, NoticeAttachmentResponse> result = new LinkedHashMap<>();
        base.forEach(item -> result.put(item.sourceKey(), item));
        for (ShAttachment attachment : attachments) {
            if (!isFloorPlanIndex(attachment.oriFileNm()) || attachment.fileSize() == null
                    || attachment.fileSize() > 2_000_000L) {
                continue;
            }
            String documentKey = documentKey(attachment);
            if (historyRepository.findByDocumentKeyAndParserVersion(
                            documentKey, MEDIA_LINK_PARSER_VERSION)
                    .filter(DocumentParseHistory::isSuccess)
                    .isPresent()) {
                continue;
            }
            try {
                byte[] bytes = documentClient.getBytes(downloadUri(detailUri, attachment));
                List<String> urls = extractUrls(bytes);
                for (String url : urls) {
                    String key = "SH_EXTERNAL_MEDIA:" + Integer.toHexString(url.hashCode());
                    result.putIfAbsent(key, new NoticeAttachmentResponse(
                            key, "EXTERNAL_FLOOR_PLAN_OR_PHOTO", null, url, null
                    ));
                }
                DocumentParseHistory history = historyRepository
                        .findByDocumentKeyAndParserVersion(documentKey, MEDIA_LINK_PARSER_VERSION)
                        .orElseGet(() -> new DocumentParseHistory(
                                documentKey, MEDIA_LINK_PARSER_VERSION
                        ));
                history.markSuccess(attachment.fileSize(), urls.size());
                historyRepository.save(history);
            } catch (RuntimeException exception) {
                log.warn("SH 도면·사진 외부 링크 추출 실패: seq={}, fileSeq={}, reason={}",
                        attachment.seq(), attachment.fileSeq(), exception.getMessage());
            }
        }
        return List.copyOf(result.values());
    }

    private List<String> extractUrls(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            List<String> result = new ArrayList<>();
            Matcher matcher = URL.matcher(text);
            while (matcher.find()) {
                result.add(matcher.group().replaceAll("[),.;]+$", ""));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("SH 도면·사진 PDF 링크를 읽지 못했습니다.", exception);
        }
    }

    private URI downloadUri(URI detailUri, ShAttachment attachment) {
        return detailUri.resolve("/app/com/file/innoFD.do?brdId=" + attachment.brdId()
                + "&seq=" + attachment.seq() + "&fileTp=" + attachment.fileTp()
                + "&fileSeq=" + attachment.fileSeq());
    }

    private boolean isHousingDocument(ShAttachment attachment) {
        String name = normalizedName(attachment.oriFileNm());
        boolean supported = name.matches(".*\\.(?:xlsx|xls|pdf|hwp|hwpx)$");
        return supported && (name.contains("주택목록") || name.contains("공급주택목록")
                || name.contains("주택내역") || name.contains("공급내역")
                || name.contains("공고문") || name.contains("입주자모집공고"));
    }

    private int priority(ShAttachment attachment) {
        String name = normalizedName(attachment.oriFileNm());
        int category;
        if ((name.contains("주택목록") || name.contains("공급주택목록")) && name.contains("상세")) {
            category = 4;
        } else if ((name.contains("주택목록") || name.contains("공급주택목록")
                || name.contains("주택내역") || name.contains("공급내역")) && !name.contains("요약")) {
            category = 3;
        } else if (name.contains("요약") && name.contains("주택")) {
            category = 2;
        } else {
            category = 1;
        }
        return category * 100_000 + revision(name) * 100 + formatPriority(name);
    }

    private int revision(String name) {
        Matcher matcher = Pattern.compile("(?:수정|변경|정정)(?<number>\\d*)").matcher(name);
        int revision = 0;
        while (matcher.find()) {
            String number = matcher.group("number");
            revision = Math.max(revision, StringUtils.hasText(number) ? Integer.parseInt(number) : 1);
        }
        return revision;
    }

    private int formatPriority(String name) {
        if (name.endsWith(".xlsx")) return 40;
        if (name.endsWith(".xls")) return 30;
        if (name.endsWith(".pdf")) return 20;
        return 10;
    }

    private String attachmentType(String fileName) {
        String name = normalizedName(fileName);
        if (isFloorPlanIndex(fileName)) return "FLOOR_PLAN_OR_PHOTO_INDEX";
        if (name.contains("주택") && name.contains("상세")) return "HOUSING_LIST_DETAIL";
        if (name.contains("주택") && name.contains("요약")) return "HOUSING_LIST_SUMMARY";
        if (name.contains("주택목록") || name.contains("공급내역") || name.contains("주택내역")) {
            return "HOUSING_LIST";
        }
        if (name.contains("공고문") || name.contains("입주자모집공고")) return "ANNOUNCEMENT";
        return "OTHER";
    }

    private boolean isFloorPlanIndex(String fileName) {
        String name = normalizedName(fileName);
        return name.contains("도면") || name.contains("평면") || name.contains("사진");
    }

    private String documentKey(ShAttachment attachment) {
        return "SH:" + attachment.brdId() + ":" + attachment.seq() + ":"
                + attachment.fileSeq() + ":" + attachment.fileSize();
    }

    private String normalizedName(String fileName) {
        return fileName == null ? "" : fileName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString().trim();
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
