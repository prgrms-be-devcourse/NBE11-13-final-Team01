package com.zipick.applicationguide.config;

/**
 * 저장된 가이드를 언제 버릴지 정하는 버전 상수.
 *
 * <b>프롬프트를 고치면 PROMPT 를, 문서 선별·축약 규칙을 고치면 REDUCER 를 올린다.</b>
 * 올리는 순간 기존에 저장된 결과는 모두 '다시 만들어야 하는 것'이 되므로,
 * "프롬프트를 고쳤는데 화면에는 예전 결과가 나온다" 같은 사고를 막을 수 있다.
 *
 * 거창한 버전 관리 장치를 만들지 않고 상수 두 개로 둔 것은 의도적이다.
 */
public final class GuideVersions {

    // v2: 실제 공고문으로 확인해 보니 '해당자 제출' 로 묶인 서류(외국인등록사실증명서·임신진단서·
    //     자동차등록증)를 통째로 빠뜨렸다. CONDITIONAL 의 정의를 넓혀 조건부 서류를 모두 담게 했다.
    public static final String PROMPT = "application-guide-v2";
    public static final String REDUCER = "reducer-v1";

    private GuideVersions() {
    }
}
