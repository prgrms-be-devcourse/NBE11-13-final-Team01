package com.zipick.notice.service;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingMediaRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class MapHousingAssetService {

    private static final Set<String> IMAGE_ATTACHMENT_TYPES = Set.of(
            "FLOOR_PLAN", "SITE_PLAN", "BUILDING_PHOTO"
    );

    private final HousingRepository housingRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final HousingMediaRepository housingMediaRepository;
    private final ExternalDocumentClient documentClient;

    public MapHousingAssetService(
            HousingRepository housingRepository,
            NoticeAttachmentRepository noticeAttachmentRepository,
            HousingMediaRepository housingMediaRepository,
            ExternalDocumentClient documentClient
    ) {
        this.housingRepository = housingRepository;
        this.noticeAttachmentRepository = noticeAttachmentRepository;
        this.housingMediaRepository = housingMediaRepository;
        this.documentClient = documentClient;
    }

    public MapHousingAsset getAttachment(Long housingId, Long attachmentId) {
        var housing = findVisibleHousing(housingId);
        var attachment = noticeAttachmentRepository.findById(attachmentId)
                .filter(candidate -> candidate.getNotice().getId().equals(housing.getNotice().getId()))
                .filter(candidate -> IMAGE_ATTACHMENT_TYPES.contains(candidate.getAttachmentType()))
                .filter(candidate -> isImage(candidate.getFileName(), candidate.getSourceUrl()))
                .orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        return download(attachment.getSourceUrl(), attachment.getFileName());
    }

    public MapHousingAsset getMedia(Long housingId, Long mediaId) {
        findVisibleHousing(housingId);
        var media = housingMediaRepository.findById(mediaId)
                .filter(candidate -> candidate.getHousing().getId().equals(housingId))
                .filter(candidate -> IMAGE_ATTACHMENT_TYPES.contains(candidate.getMediaType()))
                .filter(candidate -> isImage(candidate.getFileName(), candidate.getSourceUrl()))
                .orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        return download(media.getSourceUrl(), media.getFileName());
    }

    // 상세 화면에서 바로 쓰는 이미지·첨부라 상세 조회와 같은 상태 조건을 써야 한다.
    // 여기만 OPEN으로 남으면 공고 예정 주택의 상세는 열리는데 사진만 깨진다.
    private com.zipick.notice.domain.Housing findVisibleHousing(Long housingId) {
        return housingRepository.findByIdAndActiveTrue(housingId)
                .filter(candidate -> NoticeStatus.PUBLIC_STATUSES.contains(candidate.getNotice().getStatus()))
                .orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
    }

    private MapHousingAsset download(String sourceUrl, String fileName) {
        URI uri = URI.create(sourceUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !isAllowedHost(uri.getHost())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_FOUND);
        }
        return new MapHousingAsset(documentClient.getBytes(uri), mediaType(fileName, sourceUrl), safeFileName(fileName));
    }

    private boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("lh.or.kr") || normalized.endsWith(".lh.or.kr");
    }

    private boolean isImage(String fileName, String sourceUrl) {
        return mediaType(fileName, sourceUrl) != MediaType.APPLICATION_OCTET_STREAM;
    }

    private MediaType mediaType(String fileName, String sourceUrl) {
        String value = ((fileName == null ? "" : fileName) + " " + sourceUrl).toLowerCase(Locale.ROOT);
        if (value.matches("(?s).*\\.png(?:[?&# ].*)?")) return MediaType.IMAGE_PNG;
        if (value.matches("(?s).*\\.(?:jpg|jpeg)(?:[?&# ].*)?")) return MediaType.IMAGE_JPEG;
        if (value.matches("(?s).*\\.gif(?:[?&# ].*)?")) return MediaType.IMAGE_GIF;
        if (value.matches("(?s).*\\.webp(?:[?&# ].*)?")) return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String safeFileName(String fileName) {
        return fileName == null || fileName.isBlank() ? "housing-image" : fileName.replaceAll("[\\r\\n]", "");
    }
}
