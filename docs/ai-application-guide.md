# AI 맞춤 신청 준비 가이드

> 1차 구현 이후 실제 공고문으로 검증하며 고친 내용과 근거는
> **[실제 공고 기반 개선과 검증](ai-application-guide-improvement.md)** 에 있습니다.

관심 공고로 저장한 모집공고의 **원문(PDF/HWP)을 서버가 읽어**, 사용자가 입력한 최소 정보를 기준으로
제출서류·확인사항·유의사항을 정리해 주는 기능이다. Gemini 를 쓰지만 **AI 가 자격을 판정하지는 않는다.**

---

## 왜 만들었나

ZIPICK 의 사용자 흐름은 여기까지 이어져 있었다.

```
지도 탐색 → 맞춤 추천 → 공고 상세 → 관심 공고 저장 → 일정·D-Day 알림
                                                        ↓
                                                   ??? (빈 구간)
                                                        ↓
                                                   실제 신청 준비
```

관심 공고를 저장한 사용자가 실제로 지원하려면 **수십 쪽짜리 모집공고 PDF 를 직접 열어**
"내 경우엔 뭘 내야 하지", "이 서류는 언제 발급받은 것만 되지" 를 찾아야 했다.
이 기능은 그 구간을 메운다. 공고문에서 신청 준비에 필요한 부분만 찾아 사용자 상황에 맞춰 정리한다.

**AI 를 붙이는 것이 목적이 아니다.** 정형 데이터와 계산으로 풀 수 있는 것은 기존 코드가 그대로 하고,
AI 는 "긴 비정형 문서를 이 사람 기준으로 다시 정리하는" 일만 맡는다.

---

## 사용자 흐름

```
[주 진입점]  관심 공고·일정 화면 (/calendar)
      관심 카드의 [AI 신청 준비] 버튼
            ↓  (버튼은 화면 이동만 한다. 여기서 AI 를 부르지 않는다)
      /interest-notices/{noticeId}/guide     ← 별도 페이지

      진입 시 GET
         ├ READY          → 저장된 결과를 바로 표시 (AI 호출 0회)
         ├ NOT_GENERATED  → "만들기" 버튼만 표시
         └ UNAVAILABLE    → 사유 안내 + 원문 링크

      사용자가 [AI 신청 준비 가이드 만들기] 를 눌렀을 때만 POST
            ↓
      공고문 확보 → 텍스트 추출 → 영역 선별 → Gemini → 출처 검증 → DB 저장 → 화면

[보조 진입점]  지도 상세 패널 (HousingDetail)
      관심 저장 전 : "관심 공고로 저장하면 AI 신청 준비 가이드를 이용할 수 있어요" (조용한 힌트)
      관심 저장 후 : [AI 신청 준비 가이드 보기] → 같은 Guide 페이지로 이동
      ※ 결과 자체는 여기에 그리지 않는다. 패널 안에서 다시 스크롤하게 되기 때문이다.
```

**AI 호출 시점은 사용자가 정한다.** 화면에 들어오기만 해도 무료 사용량이 깎이면 안 되므로
GET 은 저장된 결과만 읽고, 생성은 명시적인 버튼 클릭(POST)에서만 일어난다.

관심 공고로 저장하지 않은 공고는 **서버가 403 으로 막는다**(`ApplicationGuidePersistenceService.load()`).
화면에서만 막으면 API 를 직접 불러 아무 공고에나 사용량을 쓸 수 있다.

---

## Backend 구조

패키지 `com.zipick.applicationguide`

| 구성요소 | 역할 |
| --- | --- |
| `ApplicationGuideController` | `GET` / `POST /api/v1/interest-notices/{noticeId}/application-guide` |
| `ApplicationGuideService` | 전체 흐름 조립. **트랜잭션을 걸지 않는다** (문서 다운로드·AI 호출이 길다) |
| `ApplicationGuidePersistenceService` | DB 접근 전담. 짧은 트랜잭션으로 `load()` / `save()` 만 한다 |
| `NoticeDocumentLocator` | 분석할 공고문 파일 찾기 (첨부 테이블 우선, LH 는 상세 페이지 폴백) |
| `NoticeDocumentTextExtractor` | PDFBox(쪽 단위) / Tika(HWP) 로 텍스트 추출 |
| `ApplicationSectionReducer` | 신청 준비 관련 영역만 선별하고 `SOURCE_ID` 부여 |
| `GeminiClient` | REST `generateContent` 호출. 429 만 따로 구분, 자동 재시도 없음 |
| `ApplicationGuidePrompt` | 시스템 지시문 + 사용자 메시지 + 응답 JSON 스키마 |
| `GuideResultSanitizer` | **AI 가 지어낸 출처 제거** |
| `ApplicationGuide` / `ApplicationGuideRepository` | 결과 저장 (user + notice 당 1행) |

### 트랜잭션을 나눈 이유

문서 다운로드와 Gemini 응답은 합쳐서 10초 안팎 걸린다. 그동안 DB 커넥션을 붙잡고 있으면 안 된다.
그래서 **읽을 것을 한 번에 다 읽는 `load()`** 와 **결과만 쓰는 `save()`** 로 나누고,
그 사이의 오래 걸리는 일은 트랜잭션 밖에서 처리한다. (주변 인프라 수집의 Collector / PersistenceService 분리와 같은 이유)

`open-in-view` 가 꺼져 있어 지연 로딩 엔티티를 트랜잭션 밖으로 들고 나갈 수 없으므로,
첨부·프로필은 전부 record 사본(`GuideAttachment`, `GuideUserContext`)으로 떠서 나간다.

---

## 공고문 처리

### 1. 파일 찾기 — 기관마다 다르다

| 기관 | `notice_attachment` 에 저장된 것 | 처리 |
| --- | --- | --- |
| SH / GH / HUG | 모든 첨부 (`attachment_type = ANNOUNCEMENT` 포함) | **DB 에서 바로 찾는다** |
| **LH** | **도면·평면도만** | 공고문이 DB 에 없다 → `notice.detail_url` 을 다시 읽어 첨부 목록에서 찾는다 |

LH 수집기는 모집공고 PDF/HWP 를 주택목록 추출에만 쓰고 버려서 DB 에 주소가 남지 않는다.
수집기를 고치는 편이 구조적으로 맞지만 공고 전체 재수집이 필요해, 이번에는 **조회 시점 폴백**으로 해결했다.
DB 를 먼저 보게 해 두었으므로 나중에 수집기가 공고문을 저장하기 시작하면 폴백은 저절로 안 쓰인다.

주택목록·도면·평면도·사진은 파일명과 `attachment_type` 으로 걸러낸다.
공고문이라는 단서가 전혀 없으면 분석하지 않고 "찾지 못했다"고 알린다.

### 2. 텍스트 추출

PDF 는 **쪽 단위**로 뽑는다(PDFBox). 쪽 경계가 공짜로 생기고, 출처에 실제 쪽수를 붙일 수 있다.
HWP 는 Tika 가 쪽을 주지 않아 빈 줄로 끊고, 쪽수 대신 소제목을 출처로 쓴다.

> 기존 `LhPdfHousingParser` 등을 재사용하지 않은 이유: 그 파서들은 텍스트를 뽑자마자
> 주소·보증금 정규식으로 넘어가며 **쪽 경계를 버린다.** 목적이 달라 별도 추출기를 두고
> 라이브러리(PDFBox·Tika)만 그대로 썼다. **새 의존성은 없다.**

### 3. 영역 선별 (`ApplicationSectionReducer`)

**PDF 원본 전체를 Gemini 에 보내지 않는다.** 모집공고는 100쪽이 넘기도 한다(18만 자 안팎).
그대로 보내면 분당 토큰 한도에 바로 걸리고, 응답도 느려지고, 공급주택 목록처럼 신청 준비와
무관한 문맥이 섞여 결과 품질까지 나빠진다.

네 영역의 키워드로 쪽마다 점수를 매겨 고른다.

```
ELIGIBILITY  신청자격 입주자격 신청대상 공급대상 신청순위 소득기준 자산기준 …
DOCUMENTS    제출서류 구비서류 증빙서류 서류제출 첨부서류 …
PROCESS      신청방법 접수방법 신청절차 인터넷청약 …
CAUTIONS     유의사항 주의사항 중복신청 무효 부적격 …
```

- 소제목(짧은 줄)에 키워드가 있으면 가중치 3배
- 고른 쪽의 **앞뒤 1쪽**을 문맥으로 함께 담는다 (제출서류 표가 쪽을 넘어가는 경우가 많다)
- 글자 예산(기본 30,000자) 안에서 담되, **영역별 최고 점수 쪽을 먼저** 담아 한 영역이 통째로 빠지지 않게 한다
- 공백을 모두 지우고 비교한다 — 공고문 PDF 는 표 안에서 `신 청 자 격` 처럼 글자가 벌어진 채 추출된다

> **키워드를 못 찾으면 전체 문서를 보내는 폴백은 일부러 만들지 않았다.**
> 그 폴백이 바로 150쪽을 통째로 보내는 경로가 되고, 사용량을 한 번에 태우면서 품질도 보장할 수 없다.
> `NO_RELEVANT_SECTION` 으로 알리고 원문을 안내한다.

---

## Gemini 에 보내는 것

### 사용자 정보 — 이 5개가 전부

```
나이 / 혼인 상태 / 가구원 수 / 무주택 여부 / 학생·취업준비생 여부
```

이 5개가 "이 사람이 어떤 신청자 유형인가"를 정하고, 신청 유형이 제출서류를 정한다.

### 보내지 않는 것

```
소득 실제 금액 · 자산 실제 금액 · 자동차가액
이메일 · 실명 · userId · JWT / Refresh Token
EligibilityChecker 의 Reason 텍스트 (금액이 그대로 들어 있다)
```

금액을 보내지 않는 이유는 세 가지다.
① 민감 금융정보다 (`UserProfile` 에도 암호화 TODO 가 달려 있다)
② 금액 기준 판정은 이미 서버의 `EligibilityChecker` 가 설정값으로 정확히 한다. AI 에게 산술을 시키면 판정이 두 개 생긴다
③ 금액을 몰라도 AI 는 해당 항목을 `CHECK_REQUIRED` / `CONDITIONAL` 로 분류한다 — 그게 우리가 원하는 동작이다

`GuideUserContext` record 에 **금액 필드가 아예 없어서** 실수로도 들어갈 수 없고,
`ApplicationGuidePromptTest` 가 "금액이 프롬프트에 없을 것"을 테스트로 고정해 두었다.

### 그 외

- 공고 정보: 공고명 / 공급기관 / 공공임대 유형 (이미 DB 에 있는 정형 정보는 다시 추출시키지 않는다)
- 선별된 공고문 텍스트 (`[SOURCE_ID: S1] FILE: … PAGE: 4` 머리말 포함)

---

## Gemini 응답

`responseMimeType: application/json` + `responseJsonSchema` 로 구조를 강제한다.

```json
{ "summary": "...", "personalChecks": [], "documents": [], "cautions": [] }
```

| 필드 | 값 | 의미 |
| --- | --- | --- |
| `personalChecks[].status` | `MATCHED` | 현재 정보와 일치 |
| | `CHECK_REQUIRED` | 추가 확인 필요 (가장 자주 쓰인다) |
| | `NOT_APPLICABLE` | 현재 정보상 해당 없음 (공고문이 명시적으로 제외한 경우에만) |
| `documents[].category` | `COMMON` | 공통 제출서류 |
| | `PERSONALIZED` | 내 상황 때문에 필요해 보이는 서류 |
| | `CONDITIONAL` | 조건부 — 일부 신청자만 내는 서류 전부 |
| `cautions[].kind` | `NOTICE` | 공고문에서 실제 확인된 유의사항 |
| | `MANUAL` | 사용자가 직접 확인해야 하는 사항 |

**`FAIL` 에 해당하는 상태는 만들지 않았다.** AI 가 "신청 불가" 를 말하면 틀렸을 때 사용자가 실제 기회를 잃는다.
스키마의 enum 에 아예 없어서 모델이 만들어 낼 수 없다.

프롬프트가 금지하는 것: 최종 자격 단정 / 공고문에 없는 내용 생성 / 쪽 번호 직접 작성 / 소득·자산 추측 /
이미 화면에 있는 정형 정보 반복.

---

## 출처(Source) 검증

AI 가 "모집공고.pdf 24쪽" 같은 출처를 **직접 쓰게 두지 않는다.** 지어내면 사용자가 원문에서 찾지 못하고,
그 순간 가이드 전체를 못 믿게 된다.

```
서버가 S1, S2 … 를 붙여 공고문 텍스트를 보낸다
      ↓
AI 는 sourceIds: ["S1","S3"] 처럼 받은 ID 만 돌려준다  (스키마 enum 으로도 제한)
      ↓
GuideResultSanitizer 가 "우리가 준 적 없는 ID" 를 제거한다  ← 이중 안전장치
      ↓
서버가 S1 → 실제 파일명 + 쪽수로 변환해 응답에 담는다
```

출처가 모두 걸러져도 **항목 자체는 남긴다.** 출처 없는 "주민등록등본이 필요하다"도 쓸모가 있고,
서류가 통째로 사라지는 쪽이 사용자에게 더 위험하다.

---

## 결과 저장 (Cache)

`notice_application_guide` 테이블. **(user_id, notice_id) 당 1행**, 이력은 남기지 않는다.
결과가 사용자 상황에 따라 달라지므로 캐시 단위도 user + notice 다.

저장된 결과를 그대로 쓸 수 있는 조건 — 넷이 모두 현재 값과 같을 때:

| 컬럼 | 바뀌면 |
| --- | --- |
| `prompt_version` | 프롬프트를 고치면 전건 다시 만든다 |
| `reducer_version` | 문서 선별·축약 규칙을 고치면 전건 다시 만든다 |
| `model` | 모델을 바꾸면 전건 다시 만든다 |
| `profile_updated_at` | **그 사용자가 내 정보를 고치면 그 사람 것만** 다시 만든다 |

다시 만들어야 하는 결과는 화면에 보여 주지 않는다. 사용자가 '대학생' 을 해제했는데
"현재 대학생이므로…" 로 시작하는 예전 가이드를 현재 결과처럼 보여 주면 틀린 안내가 된다.

### 쓰지 않기로 한 것

- **`inputHash`** — 해시를 계산하려면 캐시를 확인하기 전에 PDF 를 받아 텍스트를 뽑고 축약까지 끝내야 한다.
  캐시가 맞았을 때도 가장 느린 일을 매번 하게 되어 캐시의 의미가 없어진다.
- **`notice.updated_at`** — 공고 수집이 돌 때마다 `Notice.synchronize()` 가 `raw_payload`·`collected_at` 을
  다시 써서, 내용이 그대로여도 `updated_at` 이 바뀐다. 캐시 조건에 넣으면 수집 한 번에 전건 무효가 된다.

실측: 생성 **8.7초** → 캐시 적중 **0.013초**.

---

## 호출량 관리

대표 요청 1건 실측 (SH 청년 매입임대 공고문 23쪽):

```
input   약 18.3K tokens
output  약  2.0K tokens
total   약 20.4K tokens
시간    API 약 7~8초 (문서 다운로드·추출 포함 약 9초)
```

> **공고문 길이에 따라 달라진다.** 위 수치를 모든 공고에 그대로 적용하면 안 된다.

### 호출량을 막는 4겹

1. **사용자가 눌러야만** — 화면 진입(GET)은 조회만. AI 는 POST 에서만
2. **관심 저장 필수** — 저장하지 않은 공고는 서버가 403
3. **DB 캐시** — 유효하면 호출 0회
4. **일일 상한** — `APPLICATION_GUIDE_DAILY_CALL_BUDGET` (기본 200, `0` 이면 제한 없음)

재시도 정책: 429·타임아웃·5xx 는 **재시도하지 않는다**(다시 걸어도 같은 결과이고 사용량만 축낸다).
구조화 출력이 깨졌을 때만 1회 재시도한다(간헐적이고 다시 걸면 대개 성공).

분당 제어는 코드에 두지 않았다. 요청 하나의 입력 토큰이 공고문 길이에 따라 달라져
"분당 몇 건" 을 고정할 수 없기 때문이다. 사용자가 직접 눌러야만 호출되는 구조와 429 처리에 맡긴다.

> ⚠️ **`APPLICATION_GUIDE_DAILY_CALL_BUDGET` 은 이 기능 자체의 보호장치다.**
> Google 의 프로젝트·모델 quota 와는 별개이며, Gemini 사용량은 Google 프로젝트와 사용 모델 구성에 따라
> 다른 AI 기능과 공유될 수 있으므로 운영 환경에 맞춰 조정해야 한다.
> 개발 중에는 더 높여 쓸 수 있지만 저장소 기본값은 200 이다.
>
> (참고 — 개발 당시 Gemini 3.5 Flash Lite 개발용 프로젝트에서
> RPM 15 / TPM 250,000 input / RPD 500 을 확인했다. 운영 정책을 이 숫자에 고정하지는 않는다.)

## 실제 공고로 검증한 결과

SH 청년 매입임대 공고 2건을 **원문 PDF 와 한 줄씩 대조**했다.

```
제출서류 누락      0건   (p.4 목록 5종 + p.6 표 9행 전부 반영)
공고문에 없는 서류  0건   (요구 조건까지 원문과 축자 일치)
Source ID 오류     0건
최종 자격 단정     0건   ("신청 가능/불가/자격 충족" 표현 없음)
```

개인화도 실제로 동작한다 — 대학생으로 등록한 사용자에게 요약이 "만 24세 미혼 청년으로 대학생
신분이신 경우…" 로 시작하고, 금액을 보내지 않은 소득·자산 항목은 `CHECK_REQUIRED` 로 분류된다.

### Prompt v1 → v2 로 고친 것

첫 결과에서 제출서류가 6건(전부 `COMMON`)이었는데, 원문 p.6 의 **"해당자 제출" 구역 3건**
(외국인등록사실증명서·임신진단서·자동차등록증)이 통째로 빠졌다.

원인을 나눠 보니 Reducer·Schema·Source 매핑은 모두 정상이었고 **프롬프트 문제**였다.
`CONDITIONAL` 을 "신청순위·추가 조건" 으로만 설명해 "해당자 제출" 형식을 포괄하지 못했다.

정의를 넓히고 `prompt_version` 을 `v2` 로 올렸더니 9건(공통 6 + 조건부 3)으로 **누락이 사라졌다.**
버전을 올린 덕분에 기존 캐시가 자동으로 무효화되는 것도 함께 확인했다.

---

## 스캔 PDF 조사

OCR 없이 텍스트를 뽑는 구조라, 이미지로만 된 공고문은 분석할 수 없다. 실제 비율을 조사했다.

| 기관 | ANNOUNCEMENT 첨부 | 정상 Text | 스캔 | 다운로드 실패 |
| --- | --- | --- | --- | --- |
| SH | 47 | 47 | 0 | 0 |
| GH | 6 | 5 | 0 | 1 |
| **합계** | **53** | **52 (98.1%)** | **0 (0.0%)** | 1 |

**현재 수집 데이터에서 스캔 문서는 0건**이다. 52건 모두 네 영역이 정상 탐지됐다.
(SH 홈페이지에는 폰트 없이 JPEG 20장으로만 된 공고문도 실재하지만, 수집된 ANNOUNCEMENT 집합에는 없었다)

유일한 실패 1건은 스캔이 아니라 **GH 서버가 `.pdf` 주소에 277바이트짜리 XML 오류를
HTTP 200 으로 돌려준** 경우였다. 그대로 PDFBox 에 넘기면 "파일을 읽지 못했다"가 되는데
실제 원인은 다운로드 실패다. → **PDF 매직바이트(`%PDF`) 검사**를 추가해 `DOWNLOAD_FAILED` 로 분류한다.

---

## 실패 처리

가이드를 못 만든 것은 장애가 아니라 **화면이 그려야 할 상태**다. HTTP 오류로 내리지 않고
`status: UNAVAILABLE` + `reason` 으로 내린다. (주변 인프라 조회가 상태를 200 으로 내리는 것과 같은 방식)

| reason | 사용자에게 보이는 문구 | 다시 시도 |
| --- | --- | --- |
| `NO_DOCUMENT` | 분석할 수 있는 모집공고 파일을 찾지 못했어요 | ✕ (결과가 같다) |
| `SCANNED_DOCUMENT` | 공고문이 이미지로 되어 있어 내용을 읽지 못했어요 | ✕ (결과가 같다) |
| `DOWNLOAD_FAILED` | 공고문 파일을 내려받지 못했어요 | ○ |
| `EXTRACT_FAILED` | 공고문 파일을 읽지 못했어요 | ○ |
| `NO_RELEVANT_SECTION` | 공고문에서 신청 준비 관련 내용을 찾지 못했어요 | ○ |
| `AI_NOT_CONFIGURED` | AI 기능이 아직 준비되지 않았어요 | ○ |
| `QUOTA_EXCEEDED` | 오늘 사용할 수 있는 AI 분석 횟수를 모두 썼어요 | ○ |
| `AI_FAILED` | AI 분석에 실패했어요 | ○ |

**어떤 상태에서도 `noticeDetailUrl` 은 채워진다.** AI 가 실패해도 사용자는 공고 원문으로 갈 수 있어야 한다.
AI 가이드 실패가 지도·추천·관심 공고·일정 기능에 영향을 주지 않는다 (완전히 분리된 엔드포인트).

---

## Frontend

| 파일 | 역할 |
| --- | --- |
| `src/pages/ApplicationGuidePage.tsx` | 별도 Guide 페이지. 4개 상태 + 4개 영역 |
| `src/api/applicationGuide.ts` | GET/POST, 타입, enum → 한글 라벨 맵, 출처 포맷터 |
| `src/App.tsx` | `/interest-notices/:noticeId/guide` (`AccessGate need="LOGIN"`) |
| `src/pages/CalendarPage.tsx` | 주 진입점 CTA |
| `src/components/HousingDetail.tsx` | 보조 진입점 CTA (결과는 표시하지 않음) |

상태별 화면:

| 상태 | 화면 |
| --- | --- |
| `NOT_GENERATED` | "아직 생성된 신청 준비 가이드가 없어요" + [만들기] 버튼 |
| `LOADING` | "공고문을 분석하고 있어요." + 점 세 개 애니메이션. **버튼이 사라져 중복 클릭이 차단된다** |
| `READY` | ① 신청 준비 핵심 ② 내 정보 기준 확인사항 ③ 준비할 서류 ④ 꼭 확인하세요 |
| `UNAVAILABLE` | 사유 안내 + (재시도 가능한 경우) 다시 시도 버튼 |

구현 메모:

- **내부 enum 을 화면에 노출하지 않는다.** `MATCHED → "현재 정보와 일치"` 처럼 전부 한글 라벨로 바꾼다.
- **강조는 '추가 확인 필요' 하나만.** 사용자가 실제로 움직여야 하는 항목이라 주황으로 띄우고,
  '일치'는 초록 체크, '해당 없음'은 회색이다.
- **출처는 쪽수만.** 파일명이 60자쯤 되는 공고가 흔해 항목마다 반복하면 좁은 화면에서
  출처가 서류 이름보다 더 눈에 띈다. 파일명은 요약 아래에 한 번만 쓰고 항목에는 `출처 · 4쪽, 6쪽` 만 남긴다.
- **생성 요청에만 axios 타임아웃 120초.** 공용 인스턴스 기본값(10초)으로는 모자란다.
  끊기면 서버는 결과를 만들어 저장했는데 사용자만 실패 화면을 본다.
- 서류 체크박스는 눈으로 짚어 보라고 둔 것이고 **체크 상태를 저장하지 않는다**(이번 범위 밖).
- 390px 모바일에서 가로 오버플로 0건을 확인했다.

---

## 설정 구조와 환경변수

설정은 **공통 연결 정보**와 **기능별 정책**으로 나눠 둔다.

```yaml
# 공통: 어디에 어떤 자격으로 접속하는가. 이것만 다른 AI 기능과 공유한다
gemini:
  api:
    base-url: https://generativelanguage.googleapis.com
    # key 는 여기 적지 않는다 (아래 설명)

# 이 기능 정책: 모델·타임아웃·상한·축약 기준
zipick:
  application-guide:
    model: ${APPLICATION_GUIDE_MODEL:${GEMINI_MODEL:gemini-3.5-flash-lite}}
    connect-timeout: 5s
    read-timeout: 90s
    emergency-max-input-chars: 150000   # 비상 상한. 평상시 걸리지 않는다
    max-documents: 1
    neighbor-pages: 1
    min-extracted-chars: 1000
    daily-call-budget: ${APPLICATION_GUIDE_DAILY_CALL_BUDGET:}   # 비우면 기본 200
```

**왜 모델·타임아웃·상한을 공통에 두지 않았나** — 현재 Gemini 를 쓰는 기능이 셋이고 사용 방식이 다르다.
공고 PDF 파서는 PDF 원본을 통째로 보내 긴 타임아웃과 큰 출력이 필요하고, 공고 정규화는 전용 모델을 쓰며,
이 기능은 축약한 텍스트를 보내 상대적으로 짧게 끝난다.
`gemini.api.model` 처럼 이름이 하나뿐이면 한쪽이 값을 바꾸는 순간 다른 쪽 동작까지 바뀐다.

> ⚠️ **세 기능이 `GEMINI_API_KEY` 하나를 공유하므로 Google 프로젝트의 RPM / TPM / RPD 도 함께 쓴다.**
> 설정은 기능별로 나뉘어 있지만 실제 사용량은 한 프로젝트에서 빠진다.
> 다른 기능이 대량 호출 중이면 이 기능이 `QUOTA_EXCEEDED` 가 날 수 있다.

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |
| `GEMINI_API_KEY` | ○ | 없음 | **공통** 인증 키. 없으면 이 기능만 꺼지고 다른 기능은 정상 |
| `APPLICATION_GUIDE_MODEL` | ✕ | — | 이 기능만 다른 모델을 쓸 때 |
| `GEMINI_MODEL` | ✕ | — | **이 기능의 2순위 기본 모델.** 다른 AI 기능에는 적용되지 않는다 |
| `APPLICATION_GUIDE_DAILY_CALL_BUDGET` | ✕ | `200` | 이 기능의 하루 생성 호출 상한. `0` 이면 제한 없음 |

> 상한의 기본값 200 은 `application.yml` 이 아니라 `ApplicationGuideProperties` 에 있다.
> docker compose 는 `.env` 의 빈 값도 환경변수로 그대로 넘기는데, 빈 문자열은 Spring 에서 null 로
> 바인딩돼 yml placeholder 기본값(`:200`)을 건너뛴다. 기본값을 코드에 두지 않으면
> **`.env` 를 비워 둔 운영 환경만 조용히 무제한**이 된다. 로컬·Docker·CD 를 같게 만들려고 코드에 뒀다.

모델 우선순위: `APPLICATION_GUIDE_MODEL` → `GEMINI_MODEL` → `gemini-3.5-flash-lite`.
`ApplicationGuidePropertiesBindingTest` 가 실제 `application.yml` 을 읽어 이 순서를 고정한다.

> ⚠️ **`GEMINI_MODEL` 은 팀 전체 공통 모델이 아니다.** 이 기능만 이 값을 읽는다.
> 공고 PDF 파서는 `GEMINI_PDF_PARSER_MODEL` 을, 공고 정규화는 자체 설정값을 쓴다.
> 모든 AI 기능의 모델을 한 번에 바꾸는 환경변수는 없다.

### 인증 키

키는 환경변수 `GEMINI_API_KEY` 가 1순위이고, 없으면
`zipick-backend/config/application-secret.yml` 의 `gemini.api.key` 를 읽는다(`.gitignore` 대상).
예시는 `config/application-secret.example.yml` 참고.

> `application.yml` 에 `key: ${GEMINI_API_KEY:}` 로 적지 않은 이유: 환경변수가 없을 때 빈 문자열로
> 확정돼 비밀 파일의 값을 덮어쓴다. 적지 않으면 환경변수가 relaxed binding 으로 `gemini.api.key` 에
> 자동으로 들어간다. (relaxed binding 은 OS 환경변수에만 적용되므로 테스트도 그 방식으로 검증한다)

### 팀원 PDF 파서가 merge될 때

같은 원칙으로 맞추면 된다. 공통은 키·주소만, 정책은 기능 설정 아래로.

```yaml
gemini:
  api:
    base-url: ...          # 공통. 키는 gemini.api.key (환경변수 GEMINI_API_KEY)

zipick:
  application-guide:       # 이 기능
    model: ...
    read-timeout: 90s
    daily-call-budget: 200
  gemini-pdf-parser:       # 공고 수집 보조 (이름은 담당자가 정한다)
    model: ...
    timeout: 5m
    max-output-tokens: ...
    daily-call-limit: ...
```

피해야 할 것은 `gemini.api.model` / `gemini.api.timeout` / `gemini.api.daily-limit` 처럼
**이름이 같은데 의미가 다른 값을 두 기능이 공유하는 것**이다.

## 배포 체크리스트

> 전체 배포 절차와 실제 명령은 **[배포·운영 체크리스트 — 4. AI 신청 준비 가이드 배포](deployment.md#4-ai-신청-준비-가이드-배포-신규-테이블--gemini-키)** 에 있습니다. 여기서는 요지만 적습니다.

### ⚠️ 순서가 중요하다 — 백엔드보다 DDL 이 먼저

백엔드는 `ddl-auto: validate` 다. 매핑된 엔티티의 테이블이 하나라도 없으면
`EntityManagerFactory` 생성이 실패해 **애플리케이션 전체가 뜨지 않는다.** AI 기능만 꺼지는 게 아니다.

```
운영 DB 에 guide-init.sql 적용
      ↓
SELECT to_regclass('public.notice_application_guide') 로 확인
      ↓
~/zipick/.env 에 GEMINI_API_KEY 추가
      ↓
백엔드 배포 → 프론트 배포 → Smoke Test
```

영향 범위가 다른 두 가지를 구분한다.

| 빠진 것 | 영향 |
| --- | --- |
| **`notice_application_guide` 테이블** | ❌ 백엔드 **전체** 기동 실패 (`Schema-validation: missing table`) |
| `GEMINI_API_KEY` | ✅ 백엔드 정상 기동. AI 가이드 화면만 "AI 기능이 아직 준비되지 않았어요" |

ZIPICK 의 CD(`deploy/remote-deploy.sh`)는 **설계상 운영 DB 에 DDL 을 실행하지 않는다.**
기존 PostgreSQL 볼륨에서는 compose 의 `/docker-entrypoint-initdb.d` 스크립트가
**빈 볼륨으로 처음 뜰 때만** 실행되므로, 이미 운영 중인 DB 에는 사람이 한 번 적용해야 한다.
알림센터 DB(`zipick_notification`)와 같은 상황이다.

### 체크리스트

- [ ] 운영 DB 접속 확인 (가능하면 백업·스냅샷)
- [ ] `zipick-backend/db/guide-init.sql` 적용
      — `DROP` 없이 `CREATE TABLE IF NOT EXISTS` 만 쓰므로 **여러 번 실행해도 안전**하다.
        `local-init.sql` 같은 다른 초기화 SQL 을 운영 DB 에서 다시 실행하면 안 된다.
- [ ] `SELECT to_regclass('public.notice_application_guide');` 로 생성 확인
- [ ] `~/zipick/.env` 에 `GEMINI_API_KEY` 추가 (GitHub Secret 이 아니라 **서버의 .env**)
- [ ] 필요 시 `APPLICATION_GUIDE_MODEL` 설정 (기본 `gemini-3.5-flash-lite`)
- [ ] `APPLICATION_GUIDE_DAILY_CALL_BUDGET` 검토 (기본 200)
      — 이 기능 자체의 보호장치다. **Google 프로젝트·모델을 다른 AI 기능과 공유하면** 함께 조정한다.
- [ ] 백엔드 배포 → 로그에 `Schema-validation` 오류가 없는지 확인
- [ ] 프론트엔드 배포
- [ ] Smoke Test: 로그인 → 관심 공고 저장 → [AI 신청 준비] → 생성 → 4개 영역·출처·원문 링크 확인

> 롤백 관점에서는 **테이블 추가만** 하므로 안전하다. 구버전 이미지는 모르는 테이블을 무시한다.

## 이번 범위에서 하지 않은 것

OCR · RAG · Vector DB · Embedding · AI Chat/Q&A · 전체 공고 사전 분석 · AI 최종 자격판정 ·
비동기 Job/Queue · 체크리스트 상태 저장 · Housing 패널 내 결과 표시
