# 추천·필터링·관심 공고·알림

기존 README의 추천 기능 상세 내용을 분리했습니다. **실데이터 채움률·성능 수치와 일부 설계 기록은 2026-10-16 당시 측정 또는 논의 값이며, 최신 운영 데이터 지표를 뜻하지 않습니다.** 주변 인프라 연동 상태는 2026-10-19 통합 이후 기준으로 반영했습니다.

- [README](../README.md): 실행·환경 설정
- [추천 입력값·필드](recommendation-fields.md): 입력 계약과 데이터 필드(일부 과거 측정값 포함)
- [주변 인프라](housing-infra.md): 실제 추천 연동 및 0건 처리 규약
- [배포·운영](deployment.md): 알림 SMTP·인증 쿠키/CORS 설정

## 추천 알고리즘

사전 기입 정보(기본 정보 + 희망 주거조건)로 공급 호실마다 0~100점 추천도를 계산하고, 주택(housing) 단위로 묶어 가장 높은 호실을 대표로 보여 준다.
후보는 공고 수집 테이블(`notice ⨝ housing ⨝ housing_supply`)에서 읽는다. 설계 기준과 결정 기록은 문서 "추천 알고리즘 현황과 개선안"에 있다.

```
0. 후보 조회 (DB)        → 모집 상태·필터·숨긴 주택·지역을 SQL 에서 거른다 (빼는 조건은 DB, 순서와 근거는 코드)
1. 공고 지원조건 판정    → 공공임대 유형별 연령·무주택·소득(가구원 수별)·자산·자동차. 미충족 제외, 판단 불가는 '확인 필요'
2. 항목별 점수 (0~1)     → 비용·지역·면적·방 수·인프라·노후도·주택 유형
3. 가중합                → 기본 가중치 × 사용자 우선순위(1~5), 계산 가능한 항목만으로 정규화 × 지원조건 배수 (충족 1.0, 확인 필요 0.7)
4. 두 묶음으로 정리      → 순위 묶음: 비용·지역이 있는 주택만 60점 이상, 점수순, 공고당 5곳, 최대 30곳
                           정보 부족 묶음: 비용·지역이 없어 점수를 매길 수 없는 주택을 관심 지역 → 마감 임박순으로 따로
```

| 항목 | 기본 가중치 | 점수 기준 | 실데이터 채움률 (2026-10-16 측정 당시) |
| --- | --- | --- | --- |
| 비용 | 30 | 월 환산 비용(월세 + 보증금 × 이율 ÷ 12, 이율 = min(10%, 기준금리 + 2%p))이 예산 이하 1점, 30% 초과 0점. 월세가 없으면 보증금만 환산 | 55% |
| 지역 | 25 | 관심 지역("시도 시군구") 1순위 1.0, 2순위 0.8, 3순위 0.6, 그 외 0. 기본 '꼭' 조건이라 관심 지역 밖은 제외 | 100% |
| 면적 | 15 | 희망 범위 안 1점, 10㎡ 벗어날 때마다 0.25 감점. 최저주거기준(가구원 수별) 미달이면 근거에 표시 | 90% |
| 방 수 | 10 | 희망 최소 방 수 이상 1점, 1개 모자랄 때마다 0.5 감점 | 91% |
| 인프라 | 10 | 시설별 350m 이내 1점, 1,500m 0점. `SnapshotInfraDistanceProvider` 연동 완료. 수집 전인 주택은 인프라 점수 계산 제외 | 통합 이전 수치(현재 유효하지 않음) |
| 노후도 | 5 | 신축 1점, 준공 30년 0점 | 0% |
| 주택 유형 | 5 | 희망 주택 유형(아파트·다세대…)이면 1점. 공공임대 유형은 점수가 아니라 필터로만 쓴다 | 93% |

- 값이 없는 항목은 계산에서 빼고 남은 가중치를 다시 나눈다. 비용·지역(`required-items`)이 없으면 점수 대신 '정보 부족' 묶음으로 보낸다.
- 공공임대 유형은 공고 유형(`notice_type`)으로 분류하고, 비어 있으면 공고 제목으로 분류한다(`RentalType.classify`). 규칙이 없는 유형(영구임대 등)과 분류할 수 없는 공고는 통과도 탈락도 아닌 '확인 필요'다.
- 청년 매입임대는 3순위(본인 소득 1인 100%, 행복주택 자산 기준)로 판정하고, 넘더라도 2순위(부모 합산) 가능성이 있으면 '확인 필요'다. 일반 매입임대는 대상마다 조건이 달라 '확인 필요'다.
- 추천 기준(가중치·꼭/되면 좋음 규칙·인프라 가중치)은 사용자당 최대 3개 저장하고 전환할 수 있다. `MUST` 항목이 0점이면 제외한다.
- 모든 수치는 `application.yml`의 `zipick.recommendation`에서 조정한다. 지원조건 기준값은 2026년 적용 기준(마이홈·LH)이며 최종 자격은 공고문 기준이다.
- 2026-10-16 당시 측정 응답 시간: 주택 579곳·호실 1,507개 기준 약 35ms (정렬은 코드에서, 목표를 넘으면 DB 로 옮긴다).
- 엔진 단위 테스트: `zipick-backend/src/test/java/com/zipick/recommendation/engine`

## 우선순위 찾기 (AHP · 쌍대 선택 학습)

우선순위 슬라이더 5개를 처음부터 직접 맞추기 어려우니, 두 가지 방법으로 찾아 준다. 결과는 둘 다 활성 추천 기준의 우선순위(1~5)로 저장되므로 추천 엔진은 그대로다.

**AHP (온보딩 3단계)**: "월 비용이 싼 집 vs 원하는 동네" 같은 질문 3개(비용·지역·인프라의 모든 쌍)에 5단계로 답한다.

```
답 → Saaty 척도(1/5, 1/3, 1, 3, 5) → 비교 행렬 → 행 기하평균 = 가중치
우선순위 = 3 × (가중치 ÷ 가중치들의 기하평균), 1~5로 자름   ← 전부 "비슷해요"면 모두 3
```
- 답의 순서가 뒤집히면(비용>지역, 지역>인프라인데 인프라>비용) "엇갈려요"를 띄운다. 5단계 척도에선 자연스러운 답도 CR이 0.1을 넘을 수 있어, CR은 참고값으로만 내려준다.
- 면적·유형은 묻지 않고 지금 값을 유지한다.

**쌍대 선택 학습 (취향 찾기 화면 `/tune`)**: 실제 집 두 곳을 점수를 가린 채 보여 주고 더 마음에 드는 쪽을 고르게 한다.

```
d = 왼쪽 항목 점수 − 오른쪽 항목 점수
P(왼쪽) = σ(β·d)                          ← Bradley-Terry = 차이에 대한 로지스틱 회귀
손실 = −로그우도 + λ/2‖β − β₀‖²,  β₀ = 10 × 기본 가중치, λ = 1   ("비슷해요"는 목표 0.5)
학습 가중치 = β 정규화 → 기본 가중치로 나눠 우선순위로
다음 질문 = p(1−p)·‖d‖² × 0.5^(이미 나온 횟수) 가 가장 큰 쌍   ← 모델이 헷갈리는 쌍, 같은 집 반복은 피함
```
- 5번 이상 고르면 적용할 수 있다. 사전 분포가 항상 기본값이라 같은 답이면 몇 번 적용해도 결과가 같다.
- 모의 사용자 실험에서 10번 답하면 중시 항목을 우선순위 4 이상으로 찾는 비율이 약 90%였다.
- 한계: 후보가 적으면 특징이 겹친 집(예: 유형도 다르고 인프라도 나쁜 집)에서 어느 항목 때문인지 구분하지 못한다. 실데이터가 들어오면 나아진다.

## API

| Method | URI | 설명 |
| --- | --- | --- |
| GET, PUT | /api/v1/profiles/me | 기본 정보 |
| GET, PUT | /api/v1/profiles/me/preferences | 희망 주거조건 |
| GET | /api/v1/houses | 전체 공급주택을 주택 단위로 (점수 없이 필터만). 추천 목록과 같은 필터 쿼리 + `limit`(기본 500). 로그인 불필요 |
| GET | /api/v1/houses/filter-options | 필터 값별 모집 중인 주택 수(상태·기관·공공임대 유형·주택 유형·시군구). 관심 지역 선택지도 여기서 온다 |
| GET | /api/v1/recommendations | 추천 목록. 필터 쿼리: regions, depositMin/Max, rentMin/Max, areaMin/Max, housingTypes, statuses, agencies, rentalTypes, includeUnknown. `regions`는 "시도 시군구". 응답은 `houses`(순위 묶음)와 `incomplete`(정보 부족 묶음). `preview=true`면 순위 묶음 후보 전체(최대 200) |
| GET | /api/v1/recommendations/{housingId} | 주택 하나의 점수와 근거 (대표 호실 기준) |
| GET | /api/v1/recommendations/relax-suggestions | 추천이 5곳 미만일 때 조건 완화 제안 |
| GET, POST | /api/v1/recommendation-profiles | 추천 기준 목록·생성 |
| PATCH, DELETE | /api/v1/recommendation-profiles/{id} | 추천 기준 수정·삭제 |
| POST | /api/v1/recommendation-profiles/{id}/activate | 사용할 기준 전환 |
| POST, DELETE | /api/v1/recommendation-feedbacks[/{id}] | 숨김·별로예요 (같은 이유 2회면 조건 변경 제안) |
| POST | /api/v1/recommendation-tuning/ahp | AHP 답으로 우선순위 계산. `apply=true`면 저장 |
| GET | /api/v1/recommendation-tuning/pairs/next | 다음에 비교할 두 집 |
| POST, DELETE | /api/v1/recommendation-tuning/pairs | 두 집 중 고르기(LEFT/RIGHT/TIE) / 기록 초기화 |
| GET | /api/v1/recommendation-tuning/pairs/result | 지금 우선순위 vs 선택에서 찾은 우선순위 |
| POST | /api/v1/recommendation-tuning/pairs/apply | 학습 결과를 추천 기준에 적용 (5번 이상) |
| GET, POST | /api/v1/interest-notices | 관심 공고 목록(공고 요약 + D-day) / 저장 `{noticeId, memo?}` (이미 있으면 그대로 200) |
| GET | /api/v1/interest-notices/ids | 저장한 공고 id 목록 (카드의 ★ 표시용) |
| DELETE | /api/v1/interest-notices/{noticeId} | 저장 해제 |
| GET | /api/v1/interest-notices/schedule | 관심 공고의 일정(공고·접수 시작·접수 마감·당첨 발표). `from`·`to`(YYYY-MM-DD)로 기간 지정 |
| GET, PUT | /api/v1/notifications/settings | 알림 설정 (슬랙 웹훅·이메일·마감 D-day 목록·새 공고 알림). 웹훅은 끝 6자만 돌려준다 |
| POST | /api/v1/notifications/test | 테스트 알림 바로 보내기 |
| GET | /api/v1/notifications | 알림 내역 (최근 50건) |

프론트는 `preview=true`로 받은 항목별 점수로 가중치 슬라이더를 움직일 때 서버 없이 즉시 다시 순위를 매긴다(`src/utils/score.ts`, 서버와 같은 공식).

## 기본 필터링

- 추천 공급주택과 전체 공급주택이 **같은 필터**(`HouseFilterParams` → `HouseFilter` → QueryDSL)를 쓴다. 화면 위 전환 버튼으로 오가도 필터(URL 쿼리)가 유지된다.
- 필터는 저장된 추천조건을 바꾸지 않고 지금 보이는 결과만 좁힌다.
- 필터 항목: 지역("시도 시군구"), 보증금·월세·면적 범위, 주택 유형, 모집 상태(전체/공고중/접수중), 공급기관(기관 코드), 공공임대 유형, 정보 없는 집 포함 여부.
- 공급기관·유형·지역 칩은 `/filter-options`의 개수로 켜고 끈다. 수집 기관·지역이 늘면 코드 수정 없이 켜진다.

## 관심 공고 · 일정 · 알림

- **관심 공고 저장:** 카드의 ★로 공고(`notice`)를 저장한다(`interest_notices`, 사용자·공고 unique). 주택이 아니라 **공고** 단위로 저장하는 이유는 일정(접수·발표)이 공고에 붙어 있기 때문이다.
- **일정(캘린더):** 따로 저장하지 않고 수집된 공고의 `announced_at`·`apply_start_at`·`apply_end_at`·`winner_announced_at`을 그대로 읽어 일정으로 만든다. 공고가 갱신되면 일정도 같이 바뀐다. 프론트 `features/calendar/CalendarPanel`은 일정 목록만 받는 독립 컴포넌트라 다른 화면에도 붙일 수 있다.
- **알림:** 아웃박스(`notification_events`) + DB 폴링(5분, `zipick.notification.poll-interval`). 설계 비교(DB 폴링 / 메시지 큐 / Kafka)는 문서의 알림 섹션에 있다. 당시 공고 69건·사용자 수십 명 규모를 가정했을 때 브로커를 두는 비용이 이득보다 커서 DB 폴링을 골랐고, 채널(`NotificationChannel`)만 추가하면 카카오 등으로 넓힐 수 있다.
  - 종류: ① 관심 공고 **접수 마감 D-day**(기본 3·1·0일 전) ② **내 조건에 맞는 새 공고**(마지막 확인 이후 수집된 공고를 사용자별로 추천 엔진에 넣어 순위 묶음에 들면 발송, `RecommendationService.rankForNotices`).
  - 같은 알림은 `dedupe_key`(예 `DEADLINE:{user}:{notice}:{d}`) unique 로 한 번만 적히므로 주기가 겹치거나 재시작해도 중복 발송이 없다. 실패는 `max-attempts`(3)까지 재시도 후 FAILED.
  - 채널: **슬랙 incoming webhook**(사용자가 설정 화면에 URL 입력, `https://hooks.slack.com/` 으로 시작해야 저장됨 — 다른 주소로 서버가 요청을 보내는 것을 막기 위해) / **이메일**(`MAIL_HOST` 등 SMTP 환경 변수가 있을 때만 켜짐).
  - 테이블 4개(`interest_notices`, `notification_settings`, `notification_events`, `notification_cursors`)는 `db/local-init.sql` 3절에 있다.

## 다른 담당자와의 접점

- **공고 수집(김주형):** 추천은 `notice`(공고 유형·제목·상태·마감일), `housing`(시도·시군구·좌표·준공연도), `housing_supply`(면적·방 수·보증금·월세·주택 유형)를 읽기만 한다. 컬럼이 바뀌면 `CandidateQueryRepository`만 고치면 된다. 기존 측정에서 보증금·월세가 비어 있던 호실(약 45%)은 '정보 부족' 묶음으로 가므로, 첨부 파일에서 금액을 더 채우면 추천 범위가 그만큼 넓어진다.
- **지도(김주형):** 추천 응답(`houses`·`incomplete`)의 `houseId`는 `housing_id`다. 핀은 `lat`·`lng`로 그리면 되고 좌표가 채워지면 인프라·지도 모두 바로 쓸 수 있다.
- **주변 인프라(한채희):** `housing.infra.InfraDistanceProvider`를 `SnapshotInfraDistanceProvider`(`@Primary`)가 구현하며 DB에 저장된 인프라 스냅샷을 추천 점수에 반영한다. 입력은 후보 목록, 출력은 `housing_id → {카카오 카테고리 코드 → 최단 거리 m 또는 null}`이다. 수집 전 주택은 인프라 점수 계산에서 제외한다. 자세한 규약은 [주변 인프라 문서](housing-infra.md) 참고.
- 지원조건 기준값은 2026년 적용 기준이지만 공고마다 다를 수 있다. 근거 표(문서)에 출처가 있다.
- 기본 정보의 학생 구분(대학생·취업준비생)은 청년매입임대 연령 확장에만 쓴다. 청약저축·직업은 판정에 쓰지 않아 입력에서 뺐다.
- **새 공고 알림은 수집 시각(`notice.created_at`)을 기준으로 '새 공고'를 고른다.** 수집기가 기존 공고를 지웠다 다시 넣으면 새 공고로 보이니, 갱신은 update 로 해 주면 좋다(김주형).
