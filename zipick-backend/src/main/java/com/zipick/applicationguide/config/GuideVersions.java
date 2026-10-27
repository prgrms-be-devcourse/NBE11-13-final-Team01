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
    // v3: notice 26(LH 강원 매입임대) 원문 대조에서 세 가지를 고쳤다.
    //     ① 7쪽 '공통 제출서류' 표에서 조건이 옆 칸에 적힌 행(배우자 주민등록표등본·임신진단서·
    //        외국인등록증)이 입력에는 있었는데 결과에서 빠졌다 → 행 단위로 읽도록 지시를 강화.
    //     ② personalChecks 의 '3~6개' 하한 때문에 모델이 개수를 채우려 '방문접수 방법' 같은
    //        비개인화 항목을 MATCHED 로 넣었다 → 하한을 없애고 cautions 로 보내도록 했다.
    //     ③ NOT_APPLICABLE 이 '공고문이 제외한 경우' 로만 정의돼 있어, 사용자가 '해당 없음' 이라고
    //        명시한 계층까지 CHECK_REQUIRED 가 됐다(notice 109 대학생) → 정의를 넓혔다.
    public static final String PROMPT = "application-guide-v3";

    // v2: 입력 예산(max-input-chars)을 30,000 -> 60,000 으로 올리고, 덩어리가 예산을 넘을 때
    //     실제로 잘라 담도록 고쳤다. 같은 공고라도 Gemini 에 들어가는 내용이 달라지므로
    //     저장된 결과를 그대로 쓰면 안 된다.
    // v3: 상한의 역할을 바꿨다. 평상시 Reducer 가 관련 있다고 고른 덩어리를 전부 보내고,
    //     상한(emergency-max-input-chars 150,000)은 이상치에서만 작동한다.
    //     GH 경기행복주택(108쪽)이 60,000 에서 관련 쪽 47개 중 36개만 보내던 것이 47개 전부가 된다.
    //     선별 결과가 달라지므로 저장된 결과를 다시 만들어야 한다.
    public static final String REDUCER = "reducer-v3";

    private GuideVersions() {
    }
}
