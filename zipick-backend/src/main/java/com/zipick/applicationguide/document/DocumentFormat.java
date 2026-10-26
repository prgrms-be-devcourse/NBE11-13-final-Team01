package com.zipick.applicationguide.document;

import java.util.Locale;

/** 공고문 파일 형식. 텍스트를 어떻게 뽑을지와, 출처에 쪽수를 붙일 수 있는지가 달라진다 */
public enum DocumentFormat {
    /** PDFBox 로 쪽 단위 추출. 출처에 실제 쪽수를 붙일 수 있다 */
    PDF,
    /** Tika 로 통짜 추출. 쪽 경계를 알 수 없어 출처는 파일명 + 소제목으로 대신한다 */
    HWP;

    public static DocumentFormat of(String fileNameOrUrl) {
        if (fileNameOrUrl == null) return null;
        String value = fileNameOrUrl.toLowerCase(Locale.ROOT);
        if (value.contains(".pdf")) return PDF;
        if (value.contains(".hwpx") || value.contains(".hwp")) return HWP;
        return null;
    }
}
