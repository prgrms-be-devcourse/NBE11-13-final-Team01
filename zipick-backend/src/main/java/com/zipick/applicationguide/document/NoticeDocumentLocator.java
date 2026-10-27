package com.zipick.applicationguide.document;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.GuideAttachment;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공고에서 "모집공고문" 파일을 찾는다.
 *
 * <h3>왜 두 갈래인가</h3>
 * 기관마다 notice_attachment 에 저장해 둔 것이 다르다.
 * <ul>
 *   <li>SH / GH / HUG: 모든 첨부를 저장한다. attachment_type 에 ANNOUNCEMENT 가 붙어 있다.</li>
 *   <li><b>LH: 도면·평면도만 저장한다.</b> 모집공고 PDF/HWP 는 수집 과정에서 주택목록을 뽑는 데만
 *       쓰고 버려서 DB 에 주소가 남아 있지 않다.</li>
 * </ul>
 * 그래서 DB 에 쓸 만한 첨부가 없고 LH 공고이면 상세 페이지를 그 자리에서 다시 읽어 첨부 목록을 찾는다.
 *
 * 공고 수집기(LhHousingDetailSource)가 공고문도 저장하게 고치는 편이 구조적으로는 맞다.
 * 그러면 공고를 전부 다시 수집해야 하고 수집 담당자 영역을 건드리게 되어 이번에는 하지 않았다.
 * 대신 DB 를 먼저 보게 해 두었으므로, 나중에 수집기가 공고문을 저장하기 시작하면
 * 1단계에서 걸려 이 폴백은 저절로 쓰이지 않게 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeDocumentLocator {

    /**
     * 공고문이 아닌 것이 확실한 첨부 유형.
     *
     * <b>수집기가 붙이는 값을 모두 적어 둬야 한다.</b> 여기 없는 유형은 파일명 규칙으로만 걸러지는데,
     * 이름에 단서가 없으면 그대로 통과해 엉뚱한 첨부를 분석하게 된다.
     * 유형 목록은 각 수집기의 attachmentType()/mediaType() 에 있다.
     *   LH   FLOOR_PLAN / HOUSING_LIST / APPLICATION_FORM
     *   SH   EXTERNAL_FLOOR_PLAN_OR_PHOTO / FLOOR_PLAN_OR_PHOTO_INDEX / HOUSING_LIST_*
     *   GH   FLOOR_PLAN / SITE_PLAN / LOCATION_MAP / HOUSING_IMAGE
     *   GBDC FLOOR_PLAN_OR_PHOTO / HOUSING_LIST
     *
     * APPLICATION_FORM(신청서·서식)을 넣은 이유가 특히 중요하다. LH 는 파일명에 '공고' 가 없고
     * '신청'·'서식'·'양식' 이 있으면 이 유형을 붙이는데, PREFERRED_NAMES 에 "신청안내" 가 있어
     * "신청안내.hwp" 같은 서식 파일이 공고문으로 뽑힐 수 있었다. 서식은 공고문이 아니다.
     */
    private static final List<String> EXCLUDED_TYPES = List.of(
            "FLOOR_PLAN", "FLOOR_PLAN_OR_PHOTO", "FLOOR_PLAN_OR_PHOTO_INDEX",
            "EXTERNAL_FLOOR_PLAN_OR_PHOTO", "SITE_PLAN", "BUILDING_PHOTO",
            "LOCATION_MAP", "HOUSING_IMAGE", "APPLICATION_FORM",
            "HOUSING_LIST", "HOUSING_LIST_DETAIL", "HOUSING_LIST_SUMMARY");

    /** 파일명에 이 말이 있으면 공고문이 아니다. 주택목록·도면은 신청 준비 정보가 없고 길기만 하다 */
    private static final List<String> EXCLUDED_NAMES = List.of(
            "도면", "평면", "사진", "위치도", "배치도", "투시도", "조감도",
            "주택목록", "공급내역", "주택내역", "공급대상주택", "단지별", "상세내역");

    /**
     * 파일명에 이 말이 있으면 공고문일 가능성이 높다. 앞에 있을수록 점수가 높다.
     *
     * "입주자모집" 은 뒤에 "공고" 가 붙지 않는 작명을 받기 위한 것이다.
     * LH 는 "20260922철원갈말영구임대예비입주자모집.pdf" 처럼 끝을 "모집" 으로 맺는 경우가 흔한데,
     * 이 단어가 없으면 점수가 0 이 되어 명백한 모집공고문이 통째로 제외됐다(notice 34).
     * 주택목록·도면 같은 파일은 EXCLUDED_NAMES 가 점수 계산 전에 거르므로 오탐이 늘지 않는다.
     */
    private static final List<String> PREFERRED_NAMES = List.of(
            "입주자모집공고", "모집공고", "공고문", "신청안내", "공고", "입주자모집");

    private final ExternalDocumentClient documentClient;
    private final ApplicationGuideProperties properties;
    /** 기관별 상세 페이지 폴백. 기관이 늘어도 이 클래스는 바뀌지 않는다 */
    private final List<NoticeDocumentFallback> fallbacks;

    /** 분석할 공고문을 고른다. 하나도 못 찾으면 빈 목록 */
    public List<LocatedDocument> locate(GuideRequestContext context) {
        List<LocatedDocument> fromDb = select(context.attachments());
        if (!fromDb.isEmpty()) {
            log.debug("공고문을 첨부 테이블에서 찾았습니다. noticeId={}, files={}",
                    context.noticeId(), fromDb.stream().map(LocatedDocument::fileName).toList());
            return fromDb;
        }
        return locateFromDetailPage(context);
    }

    /** DB 에 저장된 첨부 중에서 고른다 */
    List<LocatedDocument> select(List<GuideAttachment> attachments) {
        return rank(attachments.stream()
                .map(a -> toCandidate(a.fileName(), a.sourceUrl(), a.attachmentType()))
                .toList());
    }

    /**
     * 상세 페이지를 그 자리에서 읽어 공고문을 찾는다. 기관별 해석은 NoticeDocumentFallback 이 맡는다.
     *
     * 실패해도 예외를 밖으로 던지지 않는다. 공고문을 못 찾은 것과 같게 처리하고
     * 사용자에게는 원문을 직접 확인하도록 안내한다. AI 기능의 실패가 다른 화면을 깨서는 안 된다.
     */
    private List<LocatedDocument> locateFromDetailPage(GuideRequestContext context) {
        String detailUrl = context.header().detailUrl();
        if (detailUrl == null || detailUrl.isBlank()) {
            return List.of();
        }
        URI detailUri;
        try {
            detailUri = URI.create(detailUrl);
        } catch (IllegalArgumentException exception) {
            log.warn("상세 주소를 읽지 못했습니다. noticeId={}", context.noticeId());
            return List.of();
        }
        NoticeDocumentFallback fallback = fallbacks.stream()
                .filter(f -> f.supports(context.source(), detailUri))
                .findFirst()
                .orElse(null);
        if (fallback == null) {
            log.info("상세 페이지를 읽을 수 있는 기관이 아닙니다. noticeId={}, source={}",
                    context.noticeId(), context.source());
            return List.of();
        }
        try {
            String html = documentClient.getText(detailUri);
            List<Candidate> candidates = fallback.findCandidates(detailUri, html);
            List<LocatedDocument> found = rank(candidates);
            log.info("상세 페이지에서 공고문을 찾았습니다. noticeId={}, source={}, 첨부={}건, 선택={}",
                    context.noticeId(), context.source(), candidates.size(),
                    found.stream().map(LocatedDocument::fileName).toList());
            return found;
        } catch (RuntimeException exception) {
            log.warn("상세 페이지에서 공고문을 찾지 못했습니다. noticeId={}, source={}, reason={}",
                    context.noticeId(), context.source(), exception.getMessage());
            return List.of();
        }
    }

    /** 점수가 높은 순으로 maxDocuments 개까지 */
    List<LocatedDocument> rank(List<Candidate> candidates) {
        return candidates.stream()
                .filter(Candidate::usable)
                .sorted(Comparator.comparingInt(Candidate::score).reversed())
                .limit(properties.maxDocuments())
                .map(c -> new LocatedDocument(c.fileName(), c.sourceUrl(), c.format(), c.score()))
                .toList();
    }

    static Candidate toCandidate(String fileName, String sourceUrl, String attachmentType) {
        String name = fileName == null ? "" : fileName.trim();
        // 파일명이 비어 있는 첨부가 있어 주소의 확장자도 함께 본다
        DocumentFormat format = DocumentFormat.of(name);
        if (format == null) {
            format = DocumentFormat.of(sourceUrl);
        }
        return new Candidate(name, sourceUrl, format, score(name, attachmentType, format));
    }

    /**
     * 점수가 음수면 제외한다.
     *
     * attachment_type 을 파일명보다 먼저 믿는다. SH·GH 수집기가 이미 분류해 둔 값이라
     * 파일명 규칙보다 정확하다. LH 폴백에는 type 이 없어 파일명만으로 판단한다.
     */
    static int score(String fileName, String attachmentType, DocumentFormat format) {
        if (format == null) {
            return -1;
        }
        String normalized = fileName.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (attachmentType != null && EXCLUDED_TYPES.contains(attachmentType)) {
            return -1;
        }
        if (EXCLUDED_NAMES.stream().anyMatch(normalized::contains)) {
            return -1;
        }

        int score = 0;
        if ("ANNOUNCEMENT".equals(attachmentType)) {
            score += 100;
        }
        for (int i = 0; i < PREFERRED_NAMES.size(); i++) {
            if (normalized.contains(PREFERRED_NAMES.get(i))) {
                score += 60 - (i * 5);
                break;
            }
        }
        // 공고문이라는 단서가 전혀 없으면 쓰지 않는다.
        // 엉뚱한 첨부를 분석해 무료 사용량을 쓰는 것보다 "찾지 못했다" 고 알리는 편이 낫다.
        if (score == 0) {
            return -1;
        }
        return score + formatBonus(format);
    }

    /**
     * 같은 공고문이 여러 형식으로 올라와 있을 때의 우선순위.
     *
     * PDF 는 쪽 경계가 있어 출처에 실제 쪽수를 붙일 수 있고, HWP 는 소제목으로 대신할 수 있다.
     * HWPX 는 읽지 못해 결국 UNSUPPORTED_DOCUMENT_FORMAT 이 되므로 가장 뒤에 둔다.
     * (분석은 1건만 하므로, 읽을 수 있는 형식이 하나라도 있으면 그쪽이 뽑혀야 한다)
     */
    private static int formatBonus(DocumentFormat format) {
        return switch (format) {
            case PDF -> 10;
            case HWP -> 5;
            case HWPX -> 0;
        };
    }

    record Candidate(String fileName, String sourceUrl, DocumentFormat format, int score) {
        boolean usable() {
            return score >= 0 && sourceUrl != null && !sourceUrl.isBlank();
        }
    }
}
