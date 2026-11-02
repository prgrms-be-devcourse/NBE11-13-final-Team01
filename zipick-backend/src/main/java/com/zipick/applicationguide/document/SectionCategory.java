package com.zipick.applicationguide.document;

import java.util.List;

/**
 * 신청 준비와 관련된 네 영역. 공고문에서 이 영역만 골라 Gemini 에 보낸다.
 *
 * 키워드는 공백을 모두 지운 상태로 비교한다. 공고문 PDF 는 표 안에서 "신 청 자 격" 처럼
 * 글자 사이가 벌어진 채 추출되는 경우가 흔해서, 공백을 그대로 두면 거의 걸리지 않는다.
 */
public enum SectionCategory {

    /** 누가 신청할 수 있는가 */
    ELIGIBILITY(List.of(
            "신청자격", "입주자격", "신청대상", "공급대상", "신청순위", "입주순위",
            "소득기준", "자산기준", "자격요건", "우선공급", "공급순위", "자격조건")),

    /** 무엇을 내야 하는가 */
    DOCUMENTS(List.of(
            "제출서류", "구비서류", "신청서류", "증빙서류", "서류제출", "제출대상",
            "첨부서류", "서류목록", "구비사항", "증명서")),

    /** 어떻게 신청하는가 */
    PROCESS(List.of(
            "신청방법", "접수방법", "신청절차", "접수절차", "온라인신청", "인터넷신청",
            "인터넷청약", "방문접수", "접수기간", "청약방법")),

    /** 무엇을 조심해야 하는가 */
    CAUTIONS(List.of(
            "유의사항", "주의사항", "중복신청", "무효", "부적격", "자격제한",
            "당첨취소", "참고사항", "안내사항"));

    private final List<String> keywords;

    SectionCategory(List<String> keywords) {
        this.keywords = List.copyOf(keywords);
    }

    public List<String> keywords() {
        return keywords;
    }
}
