package com.zipick.applicationguide.service;

import com.zipick.applicationguide.document.ReducedDocument;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideUserContext;
import com.zipick.user.domain.StudentStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 에 보낼 지시문과 응답 JSON 스키마.
 *
 * <h3>이 프롬프트의 목표</h3>
 * AI 에게 "공공임대에 대해 아는 것을 말해 달라" 가 아니라
 * <b>"내가 준 공고문 발췌 안에서만, 이 사용자 기준으로 신청 준비 정보를 추려 달라"</b> 고 시킨다.
 * 그래서 규칙 대부분이 "하지 말 것" 이다.
 *
 * <h3>특히 금지하는 것</h3>
 * <ul>
 *   <li>최종 자격 단정 — "신청 가능/불가능" 은 행정 판단이고 틀리면 사용자가 기회를 잃는다.
 *       서버의 EligibilityChecker 가 따로 하는 일이기도 하다.</li>
 *   <li>공고문에 없는 서류·조건 생성 — 일반적인 공공임대 지식으로 그럴듯하게 채우면
 *       사용자가 엉뚱한 서류를 떼러 간다.</li>
 *   <li>쪽 번호 직접 쓰기 — 우리가 준 SOURCE ID 만 돌려주게 하고, 실제 파일·쪽 변환은 서버가 한다.</li>
 *   <li>소득·자산 금액 추론 — 애초에 보내지 않는 정보라 추측하면 반드시 틀린다.</li>
 * </ul>
 */
final class ApplicationGuidePrompt {

    private ApplicationGuidePrompt() {
    }

    static final String SYSTEM_INSTRUCTION = """
            당신은 한국 공공임대주택 모집공고문을 읽고, 신청자가 '신청 준비' 단계에서 무엇을 해야 하는지
            정리해 주는 도우미입니다. 사용자는 이 공고를 관심 공고로 저장해 두고 실제 지원을 준비하는 중입니다.

            [가장 중요한 규칙]
            1. 아래 '공고문 발췌' 에 실제로 적혀 있는 내용만 사용하세요.
               일반적인 공공임대 지식으로 내용을 보충하거나 추측해서 쓰지 마세요.
               발췌에 없으면 그 항목은 아예 만들지 마세요.
            2. 신청 가능 여부를 단정하지 마세요.
               "신청 가능합니다", "신청할 수 없습니다", "자격을 충족합니다", "자격 미달입니다" 같은 표현을
               절대 쓰지 마세요. 최종 자격 판정은 공고 기관이 합니다.
               확실하지 않으면 CHECK_REQUIRED 로 두고 무엇을 확인해야 하는지 알려 주세요.
            3. 출처는 제가 드린 SOURCE_ID(S1, S2 ...)만 쓰세요.
               쪽 번호나 파일명을 직접 적지 마세요. 목록에 없는 ID 를 만들어 내지 마세요.
               근거가 되는 발췌가 없으면 sourceIds 를 빈 배열로 두세요.
            4. 사용자 정보는 제가 드린 항목이 전부입니다.
               소득·자산·자동차 가액은 드리지 않았습니다. 그 값을 추측하거나 계산하지 마세요.
               소득·자산 기준과 관련된 내용은 CHECK_REQUIRED 또는 CONDITIONAL 로 두세요.
            5. 공고명·기관·접수 일정·주택 위치·임대료처럼 이미 화면에 표시되는 정보는 반복하지 마세요.
               이 가이드는 '무엇을 준비해야 하는가' 에만 집중합니다.
            6. 모든 문장은 한국어로, 신청자에게 말하듯 간결하게 쓰세요.

            [각 항목 작성 기준]

            summary
            - 2~3문장. 신청 준비 관점의 요약입니다. 공고 소개문이 아닙니다.
            - 이 공고가 어떤 신청자를 대상으로 하는지, 준비 과정에서 특히 주의할 점이 무엇인지 적으세요.

            personalChecks
            - 제가 드린 사용자 정보와 공고문 조건을 맞춰 본 결과입니다. 3~6개.
            - MATCHED: 드린 정보와 공고문 조건이 분명히 맞는 경우.
            - CHECK_REQUIRED: 드린 정보만으로는 확정할 수 없는 경우. 가장 자주 쓰게 됩니다.
              (소득·자산 기준, 세대구성원 범위, 신청순위, 부모 소득 합산 여부 등)
            - NOT_APPLICABLE: 공고문이 명시적으로 대상에서 제외한 경우에만. 아끼세요.
            - detail 에는 왜 그렇게 봤는지와 사용자가 무엇을 확인하면 되는지를 적으세요.

            documents
            - 공고문에 적힌 제출서류만 적으세요. 빠뜨리지 말고 최대한 모두 찾아 주세요.
            - 공고문에 제출서류 표가 있으면 표의 모든 행을 확인하세요.
              '해당자 제출', '해당하는 경우에만', '○○인 경우' 처럼 일부 신청자만 내는 서류도
              빠뜨리면 안 됩니다. 사용자가 거기에 해당하는지 우리가 알 수 없다고 해서 빼지 마세요.
            - COMMON: 모든 신청자가 내는 서류.
            - PERSONALIZED: 드린 사용자 정보 때문에 필요할 가능성이 높은 서류.
              (예: 대학생으로 등록된 사용자인데 공고문이 대학생에게 재학증명서를 요구하는 경우)
            - CONDITIONAL: 일부 신청자만 내는 서류 전부.
              신청순위에 따라 달라지는 서류, '해당자 제출' 로 묶인 서류,
              특정 상황(차량 소유, 외국인 직계존속, 임신 등)일 때만 필요한 서류가 모두 여기입니다.
              애매하면 빼지 말고 여기에 두고, reason 에 '어떤 경우에 필요한지' 를 적으세요.
            - reason: 이 사용자에게 왜 필요한지 한두 문장.
            - requirements: 공고문에 적혀 있을 때만. 발급 기준일, 원본/사본, 제출 시점, 대상자 한정 조건 등.
              공고문에 없으면 빈 문자열로 두세요. 지어내지 마세요.

            cautions
            - NOTICE: 공고문에 실제로 적힌 유의사항. (중복신청 무효, 발급일 기준, 부적격 처리 등)
            - MANUAL: 드린 정보만으로는 판단할 수 없어 사용자가 직접 확인해야 하는 것.
              MANUAL 은 공고문에 없는 내용이어도 되지만, 반드시 '확인해 보라' 는 안내여야 하며
              사실을 단정해서는 안 됩니다. sourceIds 는 비워 두세요.
            """;

    /** 사용자 쪽 입력. 사용자 정보는 5개뿐이고 금액은 들어가지 않는다 */
    static String userMessage(GuideRequestContext context, ReducedDocument document) {
        return """
                [공고 정보]
                %s

                [신청자 정보] — 아래가 제가 가진 정보의 전부입니다
                %s

                [공고문 발췌] — 이 안에 있는 내용만 사용하세요
                %s
                """.formatted(noticeBlock(context), userBlock(context.user()), document.text());
    }

    private static String noticeBlock(GuideRequestContext context) {
        StringBuilder block = new StringBuilder();
        block.append("- 공고명: ").append(nullSafe(context.header().title())).append('\n');
        block.append("- 공급기관: ").append(nullSafe(context.header().agencyName())).append('\n');
        if (context.rentalType() != null) {
            block.append("- 공공임대 유형: ").append(context.rentalType().getLabel()).append('\n');
        } else if (context.noticeType() != null && !context.noticeType().isBlank()) {
            block.append("- 공고 유형: ").append(context.noticeType()).append('\n');
        }
        return block.toString().stripTrailing();
    }

    /**
     * 외부 AI 로 나가는 사용자 정보는 여기 적힌 다섯 줄이 전부다.
     * 소득·자산·자동차 가액, 이메일, 이름, 사용자 번호는 담지 않는다.
     */
    private static String userBlock(GuideUserContext user) {
        return """
                - 나이: 만 %d세
                - 혼인 상태: %s
                - 가구원 수: %d명
                - 무주택 여부: %s
                - 학생·취업준비생 여부: %s
                - 소득·자산·자동차 가액: 제공되지 않음 (추측하지 마세요)
                """.formatted(
                user.age(),
                user.married() ? "기혼" : "미혼",
                user.householdSize(),
                user.homeless() ? "무주택" : "주택 보유",
                studentLabel(user.studentStatus())
        ).stripTrailing();
    }

    private static String studentLabel(StudentStatus status) {
        if (status == null) return "해당 없음";
        return switch (status) {
            case UNIVERSITY -> "대학생(재학·입학·복학 예정)";
            case JOB_SEEKER -> "취업준비생(졸업·중퇴 후 2년 이내 미취업)";
            case NONE -> "해당 없음";
        };
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "정보 없음" : value;
    }

    /**
     * 응답 JSON 스키마.
     *
     * 자유 텍스트를 받아 다시 파싱하는 대신 스키마를 강제한다. 구조가 어긋날 확률이 훨씬 낮고,
     * enum 을 스키마에 적어 두면 모델이 임의의 상태값을 만들어 내지 못한다.
     * (예: FAIL 같은 단정적 상태를 쓸 수 없게 된다)
     *
     * @param sourceIds 이 요청에서 실제로 제공한 SOURCE ID. 스키마에 적어 두면 모델이
     *                  없는 출처를 만들어 낼 확률이 크게 줄어든다. (서버에서 한 번 더 검증한다)
     */
    static Map<String, Object> responseSchema(List<String> sourceIds) {
        // 실제로 제공한 ID 만 쓸 수 있도록 스키마에 열거해 둔다 (서버에서 한 번 더 검증한다)
        Map<String, Object> sourceIdArray = array(sourceIds.isEmpty()
                ? ordered("type", "string")
                : ordered("type", "string", "enum", sourceIds));
        sourceIdArray.put("description", "근거가 되는 공고문 발췌의 SOURCE_ID. 목록에 있는 값만 사용할 것");

        Map<String, Object> personalCheck = object(ordered(
                "title", string("확인 항목 이름 (예: 대학생 자격, 무주택 요건)"),
                "status", enumString("MATCHED", "CHECK_REQUIRED", "NOT_APPLICABLE"),
                "detail", string("왜 그렇게 봤는지와 사용자가 무엇을 확인하면 되는지"),
                "sourceIds", sourceIdArray
        ), List.of("title", "status", "detail", "sourceIds"));

        Map<String, Object> documentItem = object(ordered(
                "name", string("서류 이름 (예: 주민등록등본)"),
                "category", enumString("COMMON", "PERSONALIZED", "CONDITIONAL"),
                "reason", string("이 신청자에게 왜 필요한지"),
                "requirements", string("공고문에 적힌 발급 기준일·원본 여부·제출 시점 등. 없으면 빈 문자열"),
                "sourceIds", sourceIdArray
        ), List.of("name", "category", "reason", "requirements", "sourceIds"));

        Map<String, Object> caution = object(ordered(
                "kind", enumString("NOTICE", "MANUAL"),
                "text", string("유의사항 또는 직접 확인해야 할 내용"),
                "sourceIds", sourceIdArray
        ), List.of("kind", "text", "sourceIds"));

        return object(ordered(
                "summary", string("신청 준비 관점의 2~3문장 요약"),
                "personalChecks", array(personalCheck),
                "documents", array(documentItem),
                "cautions", array(caution)
        ), List.of("summary", "personalChecks", "documents", "cautions"));
    }

    // ---- 스키마 조립 도우미 (중첩 Map 을 읽기 좋게 쓰기 위한 것) ----

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> enumString(String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", List.of(values));
        return schema;
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
