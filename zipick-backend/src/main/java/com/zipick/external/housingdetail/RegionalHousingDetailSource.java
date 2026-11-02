package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 지역 기관 게시판의 첨부파일을 수집하고 기존 문서 파서로 보완합니다. */
@Component
@Order(20)
public class RegionalHousingDetailSource implements HousingDetailSource {
    private static final Logger log = LoggerFactory.getLogger(RegionalHousingDetailSource.class);
    private static final Set<String> HOSTS = Set.of("www.gmcc.co.kr", "www.cbdc.co.kr", "www.kohom.or.kr", "www.jpdc.co.kr");
    private static final Pattern FILE_NAME = Pattern.compile("(?i)([^\\r\\n<>]+?\\.(?:pdf|hwpx|hwp|xlsx|xls|zip))(?=\\s*(?:\\[|\\(|$))");
    private static final int MAX_ARCHIVE_ENTRY = 20 * 1024 * 1024;
    private static final int MAX_ARCHIVE_TOTAL = 40 * 1024 * 1024;
    private final ExternalDocumentClient client;
    private final LhPdfHousingParser pdf;
    private final LhHwpHousingParser hwp;
    private final LhSpreadsheetHousingParser spreadsheet;

    public RegionalHousingDetailSource(ExternalDocumentClient client, LhPdfHousingParser pdf,
                                       LhHwpHousingParser hwp, LhSpreadsheetHousingParser spreadsheet) {
        this.client = client;
        this.pdf = pdf;
        this.hwp = hwp;
        this.spreadsheet = spreadsheet;
    }

    @Override
    public boolean supports(NoticeResponse notice) {
        try {
            URI uri = URI.create(notice.url());
            return uri.getHost() != null && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public HousingDetailResult extract(NoticeResponse notice) {
        URI uri = URI.create(notice.url());
        List<NoticeAttachmentResponse> attachments = findAttachments(client.getText(uri), uri);
        List<DetailedHousingUnit> units = new ArrayList<>();
        boolean failed = false;
        for (NoticeAttachmentResponse attachment : attachments) {
            if (isForm(attachment.fileName()) && !attachment.fileName().contains("공고")) continue;
            try {
                byte[] bytes = client.getBytes(URI.create(attachment.sourceUrl()));
                if (extension(attachment.fileName()).equals("zip")) {
                    ArchiveResult result = parseZip(bytes, attachment);
                    units.addAll(result.units());
                    failed |= result.failed();
                } else {
                    units.addAll(parse(bytes, attachment.fileName(), attachment.sourceKey()));
                }
            } catch (RuntimeException exception) {
                failed = true;
                log.warn("지역 기관 첨부 처리 실패: noticeId={}, file={}, reason={}", notice.noticeId(), attachment.fileName(), exception.getMessage());
            }
        }
        if (!attachments.isEmpty() && units.isEmpty()) {
            log.info("첨부파일을 발견했으나 기본 파서에서 주택을 추출하지 못했습니다: noticeId={}, attachments={}", notice.noticeId(), attachments.size());
        }
        return new HousingDetailResult(units, attachments, Map.of(), failed || (!attachments.isEmpty() && units.isEmpty()));
    }

    List<NoticeAttachmentResponse> findAttachments(String html, URI detailUri) {
        Map<String, NoticeAttachmentResponse> found = new LinkedHashMap<>();
        String host = detailUri.getHost().toLowerCase(Locale.ROOT);
        for (Element link : Jsoup.parse(html, detailUri.toString()).select("a[href]")) {
            String href = link.attr("href");
            boolean expected = switch (host) {
                case "www.gmcc.co.kr" -> href.contains("boardDownload.es?");
                case "www.cbdc.co.kr" -> href.contains("printFileDown.do?");
                case "www.kohom.or.kr" -> href.contains("/common/download.do");
                case "www.jpdc.co.kr" -> href.contains("act=download") && sameQueryValue(detailUri, href, "seq");
                default -> false;
            };
            if (!expected) continue;
            Matcher nameMatcher = FILE_NAME.matcher(link.text().trim());
            if (!nameMatcher.find()) continue;
            String name = nameMatcher.group(1).trim().replaceFirst("^\\d+\\s*:\\s*", "");
            URI download = detailUri.resolve(href);
            if (!host.equalsIgnoreCase(download.getHost())) continue;
            String stableUrl = download.toString().replaceAll(";jsessionid=[^?]*", "");
            String key = "REGIONAL_FILE:" + hash(host + "|" + detailUri.getPath() + "|" + detailUri.getQuery() + "|" + name);
            found.putIfAbsent(name, new NoticeAttachmentResponse(key, name.contains("공고") ? "ANNOUNCEMENT" : "OTHER", name, stableUrl, null));
        }
        return List.copyOf(found.values());
    }

    private boolean sameQueryValue(URI base, String href, String key) {
        String expected = queryValue(base.getRawQuery(), key);
        return expected != null && expected.equals(queryValue(base.resolve(href).getRawQuery(), key));
    }

    private String queryValue(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        return null;
    }

    private record ArchiveResult(List<DetailedHousingUnit> units, boolean failed) { }

    private ArchiveResult parseZip(byte[] bytes, NoticeAttachmentResponse attachment) {
        try {
            return readZip(bytes, attachment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return readZip(bytes, attachment, Charset.forName("MS949"));
        }
    }

    private ArchiveResult readZip(byte[] bytes, NoticeAttachmentResponse attachment, Charset charset) {
        List<DetailedHousingUnit> units = new ArrayList<>();
        int total = 0;
        boolean failed = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), charset)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (entry.isDirectory() || (isForm(name) && !name.contains("공고")) || !Set.of("pdf", "hwp", "hwpx", "xls", "xlsx").contains(extension(name))) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = zip.read(buffer)) != -1;) {
                    total += n;
                    if (output.size() + n > MAX_ARCHIVE_ENTRY || total > MAX_ARCHIVE_TOTAL) throw new IOException("압축 해제 크기 제한 초과");
                    output.write(buffer, 0, n);
                }
                try {
                    units.addAll(parse(output.toByteArray(), name, attachment.sourceKey() + ":" + hash(name)));
                } catch (RuntimeException exception) {
                    failed = true;
                    log.warn("압축파일 내 문서 처리 실패: file={}, reason={}", name, exception.getMessage());
                }
            }
            return new ArchiveResult(List.copyOf(units), failed);
        } catch (IOException exception) {
            throw new IllegalStateException("압축파일을 읽지 못했습니다: " + attachment.fileName(), exception);
        }
    }

    private List<DetailedHousingUnit> parse(byte[] bytes, String name, String key) {
        LhAttachment attachment = new LhAttachment(key, name);
        return switch (extension(name)) {
            case "pdf" -> pdf.parse(bytes, attachment, "REGIONAL_PDF");
            case "hwp", "hwpx" -> hwp.parse(bytes, attachment, "REGIONAL_HWP");
            case "xls", "xlsx" -> spreadsheet.parse(bytes, attachment);
            default -> List.of();
        };
    }

    private boolean isForm(String name) {
        return name.contains("신청서") || name.contains("서식") || name.contains("동의서") || name.contains("양식");
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
