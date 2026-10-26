package com.zipick.applicationguide.document;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.GuideAttachment;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.external.housingdetail.ExternalDocumentClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final String LH_SOURCE = "LH";

    /** LH 상세 페이지의 첨부 링크. 수집기(LhHousingDetailSource)가 보는 것과 같은 형태다 */
    private static final Pattern LH_ATTACHMENT = Pattern.compile(
            "fileDownLoad\\('(?<id>\\d+)'\\);?\"?>(?<name>[^<]+)</a>", Pattern.CASE_INSENSITIVE);
    private static final String LH_DOWNLOAD_PATH = "/lhapply/lhFile.do?fileid=";

    /** 공고문이 아닌 것이 확실한 첨부 유형 */
    private static final List<String> EXCLUDED_TYPES = List.of(
            "FLOOR_PLAN", "FLOOR_PLAN_OR_PHOTO_INDEX", "SITE_PLAN", "BUILDING_PHOTO",
            "HOUSING_LIST", "HOUSING_LIST_DETAIL", "HOUSING_LIST_SUMMARY", "EXTERNAL_FLOOR_PLAN_OR_PHOTO");

    /** 파일명에 이 말이 있으면 공고문이 아니다. 주택목록·도면은 신청 준비 정보가 없고 길기만 하다 */
    private static final List<String> EXCLUDED_NAMES = List.of(
            "도면", "평면", "사진", "위치도", "배치도", "투시도", "조감도",
            "주택목록", "공급내역", "주택내역", "공급대상주택", "단지별", "상세내역");

    /** 파일명에 이 말이 있으면 공고문일 가능성이 높다. 앞에 있을수록 점수가 높다 */
    private static final List<String> PREFERRED_NAMES = List.of(
            "입주자모집공고", "모집공고", "공고문", "신청안내", "공고");

    private final ExternalDocumentClient documentClient;
    private final ApplicationGuideProperties properties;

    /** 분석할 공고문을 고른다. 하나도 못 찾으면 빈 목록 */
    public List<LocatedDocument> locate(GuideRequestContext context) {
        List<LocatedDocument> fromDb = select(context.attachments());
        if (!fromDb.isEmpty()) {
            log.debug("공고문을 첨부 테이블에서 찾았습니다. noticeId={}, files={}",
                    context.noticeId(), fromDb.stream().map(LocatedDocument::fileName).toList());
            return fromDb;
        }
        if (!LH_SOURCE.equalsIgnoreCase(context.source())) {
            return List.of();
        }
        return locateFromLhDetailPage(context);
    }

    /** DB 에 저장된 첨부 중에서 고른다 */
    List<LocatedDocument> select(List<GuideAttachment> attachments) {
        return rank(attachments.stream()
                .map(a -> toCandidate(a.fileName(), a.sourceUrl(), a.attachmentType()))
                .toList());
    }

    /**
     * LH 상세 페이지를 다시 읽어 첨부 목록에서 공고문을 찾는다.
     *
     * 실패해도 예외를 밖으로 던지지 않는다. 공고문을 못 찾은 것과 같게 처리하고
     * 사용자에게는 원문을 직접 확인하도록 안내한다. AI 기능의 실패가 다른 화면을 깨서는 안 된다.
     */
    private List<LocatedDocument> locateFromLhDetailPage(GuideRequestContext context) {
        String detailUrl = context.header().detailUrl();
        if (detailUrl == null || detailUrl.isBlank()) {
            return List.of();
        }
        try {
            URI detailUri = URI.create(detailUrl);
            String html = documentClient.getText(detailUri);
            List<Candidate> candidates = parseLhAttachments(detailUri, html);
            List<LocatedDocument> found = rank(candidates);
            log.info("LH 상세 페이지에서 공고문을 찾았습니다. noticeId={}, 첨부={}건, 선택={}",
                    context.noticeId(), candidates.size(), found.stream().map(LocatedDocument::fileName).toList());
            return found;
        } catch (RuntimeException exception) {
            log.warn("LH 상세 페이지에서 공고문을 찾지 못했습니다. noticeId={}, reason={}",
                    context.noticeId(), exception.getMessage());
            return List.of();
        }
    }

    /** HTML 에서 첨부 링크를 읽어 후보로 만든다 (테스트에서 HTML 만으로 검증할 수 있게 분리) */
    List<Candidate> parseLhAttachments(URI detailUri, String html) {
        List<Candidate> candidates = new ArrayList<>();
        Matcher matcher = LH_ATTACHMENT.matcher(html);
        while (matcher.find()) {
            String fileName = decodeHtml(matcher.group("name"));
            String url = detailUri.resolve(LH_DOWNLOAD_PATH + matcher.group("id")).toString();
            candidates.add(toCandidate(fileName, url, null));
        }
        return candidates;
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
        // 같은 내용이면 PDF 가 낫다. 쪽 경계가 있어 출처에 쪽수를 붙일 수 있다
        return score + (format == DocumentFormat.PDF ? 10 : 0);
    }

    private static String decodeHtml(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    record Candidate(String fileName, String sourceUrl, DocumentFormat format, int score) {
        boolean usable() {
            return score >= 0 && sourceUrl != null && !sourceUrl.isBlank();
        }
    }
}
