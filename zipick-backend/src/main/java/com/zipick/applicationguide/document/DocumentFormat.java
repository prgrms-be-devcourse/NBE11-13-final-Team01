package com.zipick.applicationguide.document;

import java.util.Locale;

/** 공고문 파일 형식. 텍스트를 어떻게 뽑을지와, 출처에 쪽수를 붙일 수 있는지가 달라진다 */
public enum DocumentFormat {
    /** PDFBox 로 쪽 단위 추출. 출처에 실제 쪽수를 붙일 수 있다 */
    PDF,
    /** Tika 의 HwpV5Parser 로 통짜 추출. 쪽 경계를 알 수 없어 출처는 파일명 + 소제목으로 대신한다 */
    HWP,
    /**
     * HWPX(한글 문서를 zip+xml 로 담은 형식). <b>지원하지 않는다.</b>
     *
     * Tika 4.1.0 이 가진 한글 파서는 HwpV5Parser 하나뿐이고 지원 타입이 application/x-hwp-v5 뿐이다.
     * HWPX 는 application/hwp+zip(= zip 하위 타입)이라 전용 파서가 없어 PackageParser 로 떨어지고,
     * 내부 XML 의 텍스트 노드만 긁혀 나와 단락 구분이 사라진 덩어리 하나가 된다.
     * 그 상태로는 쪽/소제목 출처도 만들 수 없고 품질을 보장할 수 없어 분석하지 않는다.
     *
     * HWP 와 구분해 두는 이유는 두 가지다.
     *  - 사용자에게 "이미지라서 못 읽었다"(SCANNED_DOCUMENT)가 아니라 실제 원인을 알려 주기 위해
     *  - 같은 공고에 HWP 와 HWPX 가 함께 있을 때 읽을 수 있는 HWP 를 먼저 고르기 위해
     */
    HWPX;

    public static DocumentFormat of(String fileNameOrUrl) {
        if (fileNameOrUrl == null) return null;
        String value = fileNameOrUrl.toLowerCase(Locale.ROOT);
        if (value.contains(".pdf")) return PDF;
        // .hwpx 를 .hwp 보다 먼저 본다. 순서를 바꾸면 hwpx 가 hwp 로 잡힌다
        if (value.contains(".hwpx")) return HWPX;
        if (value.contains(".hwp")) return HWP;
        return null;
    }
}
