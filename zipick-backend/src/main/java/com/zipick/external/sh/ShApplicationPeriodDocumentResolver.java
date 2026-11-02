package com.zipick.external.sh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 상세 HTML에 접수기간이 없을 때 공고문 첨부파일의 텍스트에서만 기간을 보완합니다. */
@Component
public class ShApplicationPeriodDocumentResolver {

    private static final Logger log = LoggerFactory.getLogger(ShApplicationPeriodDocumentResolver.class);

    private final ExternalDocumentClient documentClient;
    private final ShHousingDetailSource detailSource;
    private final ShApplicationPeriodParser parser;
    private final Tika tika = new Tika();

    public ShApplicationPeriodDocumentResolver(
            ExternalDocumentClient documentClient,
            ShHousingDetailSource detailSource,
            ShApplicationPeriodParser parser
    ) {
        this.documentClient = documentClient;
        this.detailSource = detailSource;
        this.parser = parser;
    }

    public Optional<ShApplicationPeriod> resolve(
            URI detailUri,
            String detailHtml,
            LocalDate announcedAt
    ) {
        List<ShAttachment> candidates = detailSource.findAttachments(detailHtml).stream()
                .filter(this::isAnnouncement)
                .sorted(Comparator.comparingInt(this::priority).reversed())
                .limit(3)
                .toList();
        for (ShAttachment attachment : candidates) {
            try {
                byte[] bytes = documentClient.getBytes(detailSource.downloadUri(detailUri, attachment));
                Optional<ShApplicationPeriod> result = parser.parseDocumentText(extractText(bytes, attachment.oriFileNm()))
                        .filter(period -> isPlausible(period, announcedAt));
                if (result.isPresent()) {
                    log.info("SH 첨부 공고문에서 접수기간을 보완했습니다: seq={}, fileSeq={}, start={}, end={}",
                            attachment.seq(), attachment.fileSeq(),
                            result.get().startDate(), result.get().endDate());
                    return result;
                }
            } catch (RuntimeException exception) {
                log.warn("SH 첨부 공고문의 접수기간을 읽지 못했습니다: seq={}, fileSeq={}, reason={}",
                        attachment.seq(), attachment.fileSeq(), exception.getMessage());
            }
        }
        return Optional.empty();
    }

    boolean isPlausible(ShApplicationPeriod period, LocalDate announcedAt) {
        if (announcedAt == null) return true;
        // 공고문 안의 과거 법령 시행일·서식 작성일을 접수일로 오인하지 않는다.
        return !period.startDate().isBefore(announcedAt.minusDays(180))
                && !period.startDate().isAfter(announcedAt.plusYears(2));
    }

    private String extractText(byte[] bytes, String fileName) {
        String normalized = normalize(fileName);
        try {
            if (normalized.endsWith(".pdf")) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    return new PDFTextStripper().getText(document);
                }
            }
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                return tika.parseToString(input);
            }
        } catch (IOException | org.apache.tika.exception.TikaException exception) {
            throw new IllegalStateException("첨부 공고문 텍스트 추출 실패", exception);
        }
    }

    private boolean isAnnouncement(ShAttachment attachment) {
        String name = normalize(attachment.oriFileNm());
        boolean supported = name.endsWith(".pdf") || name.endsWith(".hwp") || name.endsWith(".hwpx");
        boolean announcement = name.contains("공고문") || name.contains("입주자모집공고");
        boolean excluded = name.contains("주택목록") || name.contains("도면") || name.contains("사진")
                || name.contains("q&a") || name.contains("qna") || name.contains("질의");
        return supported && announcement && !excluded;
    }

    private int priority(ShAttachment attachment) {
        String name = normalize(attachment.oriFileNm());
        int revised = name.contains("수정") || name.contains("정정") || name.contains("변경") ? 100 : 0;
        int format = name.endsWith(".pdf") ? 20 : 10;
        return revised + format;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
