package com.zipick.applicationguide.document;

import java.net.URI;
import java.util.List;

/**
 * 공고문 첨부가 DB 에 없을 때, 기관 상세 페이지를 그 자리에서 읽어 후보를 찾는 전략.
 *
 * <h3>왜 인터페이스로 뺐나</h3>
 * 기관이 늘어날 때마다 {@link NoticeDocumentLocator} 안에 if 가 쌓이는 것을 막기 위해서다.
 * 기관마다 다른 것은 <b>"상세 HTML 에서 첨부 링크를 어떻게 읽어 내는가" 하나뿐</b>이고,
 * 그 뒤의 이름 점수·형식 판정·정렬·개수 제한은 이미 기관과 무관하게 공용이다.
 * 그래서 이 인터페이스는 HTML 을 후보 목록으로 바꾸는 일만 맡는다.
 *
 * <h3>근본 해법은 수집기 쪽이다</h3>
 * 공고 수집기가 공고문 첨부까지 저장하면 Locator 가 DB 에서 먼저 찾아내므로 이 폴백은 쓰이지 않는다.
 * 수집기 담당 영역이라 이번에는 건드리지 않고 조회 시점 폴백으로 메운다.
 */
public interface NoticeDocumentFallback {

    /** 이 공고를 맡을 수 있는가. source 코드나 상세 주소의 호스트로 판단한다 */
    boolean supports(String source, URI detailUri);

    /**
     * 상세 페이지 HTML 에서 공고문 후보를 뽑는다.
     *
     * 점수 계산은 하지 않는다. 파일명과 내려받을 주소만 만들어 주면
     * {@link NoticeDocumentLocator} 가 공용 규칙으로 고른다.
     */
    List<NoticeDocumentLocator.Candidate> findCandidates(URI detailUri, String html);
}
