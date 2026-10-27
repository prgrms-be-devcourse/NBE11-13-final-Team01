package com.zipick.applicationguide.document;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * LH 청약플러스 상세 페이지에서 공고문을 찾는다.
 *
 * LH 수집기(LhHousingDetailSource)는 모집공고 PDF/HWP 를 주택목록 추출에만 쓰고 버려서
 * notice_attachment 에는 도면·평면도만 남는다. 그래서 조회 시점에 상세 페이지를 다시 읽는다.
 *
 * 첨부 링크 모양은 수집기가 보는 것과 같다. 다만 따옴표를 선택으로 둬서 조금 더 너그럽게 받는다.
 */
@Component
public class LhDocumentFallback implements NoticeDocumentFallback {

    private static final String SOURCE = "LH";
    private static final String HOST_SUFFIX = "apply.lh.or.kr";

    private static final Pattern ATTACHMENT = Pattern.compile(
            "fileDownLoad\\('(?<id>\\d+)'\\);?\"?>(?<name>[^<]+)</a>", Pattern.CASE_INSENSITIVE);
    private static final String DOWNLOAD_PATH = "/lhapply/lhFile.do?fileid=";

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
        Matcher matcher = ATTACHMENT.matcher(html);
        while (matcher.find()) {
            String fileName = HtmlText.decode(matcher.group("name"));
            String url = detailUri.resolve(DOWNLOAD_PATH + matcher.group("id")).toString();
            candidates.add(NoticeDocumentLocator.toCandidate(fileName, url, null));
        }
        return candidates;
    }
}
