package com.zipick.applicationguide.document;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 경상북도개발공사(GBDC) 상세 페이지에서 공고문을 찾는다.
 *
 * <h3>왜 필요한가</h3>
 * GBDC 공고는 마이홈 공공데이터 API 로 수집되는데, 그 API 는 첨부파일 목록을 주지 않는다.
 * (MyHomeNoticeAggregator 가 attachments 를 항상 빈 목록으로 만든다)
 * GBDC 전용 HousingDetailSource 도 없어 notice_attachment 가 0행이고,
 * 그 결과 실제 사이트에는 모집공고 PDF 가 멀쩡히 있는데도 NO_DOCUMENT 가 됐다.
 *
 * <h3>링크 모양</h3>
 * 화면상 버튼은 javascript:void(0) 로 보이지만, 실제 앵커에는 평범한 GET 주소가 들어 있고
 * <b>원본 파일명이 fileOrgName 에 URL 인코딩되어 함께 들어 있다.</b> 그래서 따로 파일명을
 * 긁어낼 필요가 없다.
 *
 * <pre>
 * /common/download.do?fileVirtualPath=%2fprivatefiles%2fBOARD%2f...%2f782f1272-....pdf
 *                    &fileOrgName=1-1.+2026년+영주시+드림주택(청년+매입임대주택)+입주자+및+예비입주자+모집공고.pdf
 * </pre>
 *
 * 고르는 일은 하지 않는다. 주택목록·평면도·제출서류 양식은 공용 규칙(EXCLUDED_NAMES)이 거르고,
 * 모집공고 PDF 가 점수로 뽑힌다.
 */
@Component
public class GbdcDocumentFallback implements NoticeDocumentFallback {

    private static final String SOURCE = "GBDC";
    private static final String HOST_SUFFIX = "gbdc.co.kr";

    /** href 안의 다운로드 주소. fileOrgName 이 뒤에 오는 형태만 받는다 */
    private static final Pattern DOWNLOAD = Pattern.compile(
            "(?<path>/common/download\\.do\\?fileVirtualPath=[^\"'\\s>]*?&(?:amp;)?fileOrgName=(?<name>[^\"'\\s>]+))",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String source, URI detailUri) {
        if (SOURCE.equalsIgnoreCase(source)) {
            return true;
        }
        String host = detailUri == null ? null : detailUri.getHost();
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(HOST_SUFFIX);
    }

    @Override
    public List<NoticeDocumentLocator.Candidate> findCandidates(URI detailUri, String html) {
        List<NoticeDocumentLocator.Candidate> candidates = new ArrayList<>();
        Matcher matcher = DOWNLOAD.matcher(html);
        while (matcher.find()) {
            String fileName = decodeFileName(matcher.group("name"));
            String href = HtmlText.decode(matcher.group("path"));
            candidates.add(NoticeDocumentLocator.toCandidate(
                    fileName, detailUri.resolve(href).toString(), null));
        }
        return candidates;
    }

    /** fileOrgName 은 URL 인코딩이고 공백이 '+' 로 들어온다 */
    private static String decodeFileName(String encoded) {
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException exception) {
            // 인코딩이 깨져 있어도 전체를 포기하지 않는다. 형식을 못 읽으면 공용 규칙이 알아서 거른다
            return encoded;
        }
    }
}
