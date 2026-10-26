package com.zipick.applicationguide.document;

import com.zipick.applicationguide.dto.GuideSourceRef;

/**
 * 공고문에서 뽑아낸 덩어리 하나.
 *
 * PDF 는 한 쪽이 한 덩어리다. 쪽 단위로 끊는 이유는 두 가지다.
 *  - PDFBox 가 쪽 경계를 공짜로 알려 준다 (따로 제목을 파싱할 필요가 없다)
 *  - 출처에 실제 쪽수를 붙일 수 있어 사용자가 원문에서 바로 확인할 수 있다
 * 한국 모집공고는 "제출서류" 표가 한두 쪽에 모여 있어 쪽 경계가 의미 단위와 거의 맞는다.
 *
 * HWP 는 Tika 가 쪽을 알려 주지 않아 빈 줄 기준으로 끊고, 쪽수 대신 첫 줄을 소제목으로 쓴다.
 *
 * @param index 문서 안 순서 (0부터). 앞뒤 덩어리를 함께 담을 때 쓴다
 * @param page  PDF 의 쪽 번호 (1부터). HWP 는 null
 */
public record DocumentBlock(int index, Integer page, String section, String text) {

    public int length() {
        return text == null ? 0 : text.length();
    }

    public GuideSourceRef toSourceRef(String fileName) {
        return page != null ? GuideSourceRef.ofPage(fileName, page) : GuideSourceRef.ofSection(fileName, section);
    }
}
