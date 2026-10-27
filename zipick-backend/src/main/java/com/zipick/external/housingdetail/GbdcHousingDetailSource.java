package com.zipick.external.housingdetail;

import com.zipick.external.ai.GeminiPdfHousingDetailSupport;
import com.zipick.external.sh.ShPdfHousingParser;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 경상북도개발공사 상세 페이지의 공고문과 공급주택목록 첨부를 수집한다. */
@Component
@Order(15)
public class GbdcHousingDetailSource implements HousingDetailSource {

    private static final Pattern VIEWER_ARGUMENTS = Pattern.compile(
            "pdfViewr\\('(?<name>[^']+)'\\s*,\\s*'(?<ext>[^']+)'\\s*,\\s*'[^']*'\\s*,\\s*'(?<url>[^']+)'\\)",
            Pattern.CASE_INSENSITIVE
    );

    private final ExternalDocumentClient documentClient;
    private final LhSpreadsheetHousingParser spreadsheetParser;
    private final LhPdfHousingParser pdfParser;
    private final LhHwpHousingParser hwpParser;
    private final ShPdfHousingParser structuredPdfParser;
    private GeminiPdfHousingDetailSupport geminiSupport;

    public GbdcHousingDetailSource(
            ExternalDocumentClient documentClient,
            LhSpreadsheetHousingParser spreadsheetParser,
            LhPdfHousingParser pdfParser,
            LhHwpHousingParser hwpParser,
            ShPdfHousingParser structuredPdfParser
    ) {
        this.documentClient = documentClient;
        this.spreadsheetParser = spreadsheetParser;
        this.pdfParser = pdfParser;
        this.hwpParser = hwpParser;
        this.structuredPdfParser = structuredPdfParser;
    }

    @Autowired(required = false)
    void setGeminiSupport(GeminiPdfHousingDetailSupport geminiSupport) {
        this.geminiSupport = geminiSupport;
    }

    @Override
    public boolean supports(NoticeResponse notice) {
        if ("GBDC".equalsIgnoreCase(notice.source())) return true;
        if (!StringUtils.hasText(notice.url())) return false;
        URI uri = URI.create(notice.url());
        return uri.getHost() != null && uri.getHost().toLowerCase(Locale.ROOT).endsWith("gbdc.co.kr");
    }

    @Override
    public HousingDetailResult extract(NoticeResponse notice) {
        URI detailUri = URI.create(notice.url());
        List<GbdcAttachment> attachments = findAttachments(documentClient.getText(detailUri), detailUri);
        List<NoticeAttachmentResponse> saved = attachments.stream()
                .map(this::toResponse)
                .toList();

        for (GbdcAttachment attachment : housingCandidates(attachments)) {
            try {
                byte[] bytes = documentClient.getBytes(URI.create(attachment.downloadUrl()));
                List<DetailedHousingUnit> units = parse(notice, attachment, bytes);
                if (!units.isEmpty()) {
                    return new HousingDetailResult(units, saved, Map.of());
                }
            } catch (RuntimeException ignored) {
                // 다른 형식의 동일 문서 또는 다음 후보 첨부를 계속 확인한다.
            }
        }
        return new HousingDetailResult(List.of(), saved, Map.of());
    }

    List<GbdcAttachment> findAttachments(String html, URI detailUri) {
        Document document = Jsoup.parse(html, detailUri.toString());
        List<GbdcAttachment> result = new ArrayList<>();
        for (Element link : document.select("a[onclick*=pdfViewr]")) {
            Matcher matcher = VIEWER_ARGUMENTS.matcher(link.attr("onclick"));
            if (!matcher.find()) continue;
            String name = matcher.group("name").trim();
            String extension = matcher.group("ext").trim().toLowerCase(Locale.ROOT);
            String downloadUrl = detailUri.resolve(matcher.group("url").replace("&amp;", "&")).toString();
            String sourceKey = "GBDC_FILE:" + shortHash(downloadUrl);
            result.add(new GbdcAttachment(sourceKey, name, extension, downloadUrl));
        }
        return result.stream().distinct().toList();
    }

    List<GbdcAttachment> housingCandidates(List<GbdcAttachment> attachments) {
        return attachments.stream()
                .filter(this::isSupported)
                .filter(item -> !normalized(item.fileName()).matches(".*(?:신청서류|동의서|서식|양식).*"))
                .sorted(Comparator.comparingInt(this::priority).reversed())
                .toList();
    }

    private List<DetailedHousingUnit> parse(NoticeResponse notice, GbdcAttachment attachment, byte[] bytes) {
        LhAttachment compatible = new LhAttachment(attachment.sourceKey(), attachment.fileName());
        return switch (attachment.extension()) {
            case "xlsx", "xls" -> spreadsheetParser.parse(bytes, compatible);
            case "hwp", "hwpx" -> hwpParser.parse(bytes, compatible, housingListPrefix(attachment));
            case "pdf" -> {
                List<DetailedHousingUnit> structured = structuredPdfParser.parse(
                        bytes, compatible, housingListPrefix(attachment));
                List<DetailedHousingUnit> fallback = structured.isEmpty()
                        ? pdfParser.parse(bytes, compatible, housingListPrefix(attachment)) : structured;
                yield geminiSupport == null ? fallback : geminiSupport.supplement(
                        bytes, attachment.sourceKey(), "GBDC", notice.title(), attachment.fileName(), fallback);
            }
            default -> List.of();
        };
    }

    private String housingListPrefix(GbdcAttachment attachment) {
        return isHousingList(attachment.fileName()) ? "GBDC_LIST" : "GBDC_NOTICE";
    }

    private NoticeAttachmentResponse toResponse(GbdcAttachment attachment) {
        return new NoticeAttachmentResponse(
                attachment.sourceKey(), attachmentType(attachment.fileName()), attachment.fileName(),
                attachment.downloadUrl(), null
        );
    }

    private boolean isSupported(GbdcAttachment attachment) {
        return List.of("xlsx", "xls", "pdf", "hwp", "hwpx").contains(attachment.extension());
    }

    private int priority(GbdcAttachment attachment) {
        String name = normalized(attachment.fileName());
        int category = isHousingList(name) ? 3 : name.contains("공고") ? 2 : 1;
        int format = switch (attachment.extension()) {
            case "xlsx" -> 40;
            case "xls" -> 30;
            case "pdf" -> 20;
            default -> 10;
        };
        return category * 100 + format;
    }

    private boolean isHousingList(String fileName) {
        String name = normalized(fileName);
        return name.matches(".*(?:주택(?:목록|내역|명세)|공급대상주택|공급주택).*" );
    }

    private String attachmentType(String fileName) {
        String name = normalized(fileName);
        if (name.contains("평면") || name.contains("도면") || name.contains("사진")) return "FLOOR_PLAN_OR_PHOTO";
        if (isHousingList(name)) return "HOUSING_LIST";
        if (name.contains("공고")) return "ANNOUNCEMENT";
        return "OTHER";
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s._-]", "");
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
