package com.zipick.applicationguide.document;

/**
 * 분석 대상으로 고른 공고문 한 건.
 *
 * @param fileName  화면 출처 표시에 쓰는 이름
 * @param sourceUrl 내려받을 주소
 * @param format    PDF / HWP
 * @param score     선택 점수. 로그에서 "왜 이 파일을 골랐는지" 확인할 때 쓴다
 */
public record LocatedDocument(String fileName, String sourceUrl, DocumentFormat format, int score) {
}
