# ZIPICK 지도·인증 영역 인계 문서

작성 시점: 2026-10-23 · 기준 커밋: `dev` (PR #28, #29 병합 완료)

이 문서는 **지도 탐색 · 맞춤 추천 UI · 인증 화면 · 공개 공고 상세** 영역을 이어받는 사람을 위한 것이다.
무엇이 돌아가고, 왜 그렇게 짰고, 어디가 비어 있고, 무엇을 조심해야 하는지를 적었다.

관련 문서
- 지도 군집화 개선 계획 → [`docs/map-clustering.md`](./map-clustering.md)
- 인증 상세 → [`docs/auth.md`](./auth.md)
- 추천 알고리즘 → [`docs/recommendation.md`](./recommendation.md)
- 주변 인프라 → [`docs/housing-infra.md`](./housing-infra.md)

---

## 1. 지금 돌아가는 것

### 화면 구조

```
/map                        기본 진입. 전체 공고 지도 (비회원 가능)
/map?mode=recommended       맞춤 추천 지도 (로그인 + 온보딩 완료 필요)
/map?housing=123            123번 주택 상세 패널 열림
/houses/:houseId            공개 공고 상세 페이지
/login  /signup             인증
/onboarding  /tune          내 조건 입력 · 취향 찾기(AHP)
/calendar                   관심 공고 일정

/houses            → /map 으로 리다이렉트 (필터 쿼리 유지)
/recommendations   → /map?mode=recommended 로 리다이렉트
```

**전체 공고와 맞춤 추천은 별도 페이지가 아니라 같은 `MapPanel` 위의 두 모드다.**
모드·선택한 핀·필터가 전부 URL 쿼리에 있어서, 로그인하러 갔다 돌아와도 같은 화면이 복원된다.

### 모드별 차이

| | 전체 공고 | 맞춤 추천 |
| --- | --- | --- |
| 데이터 | `GET /api/v1/map/housings` | `GET /api/v1/recommendations` |
| 접근 | 비회원 가능 | 로그인 + 온보딩 완료 |
| 기본 필터 패널 | 있음 | **없음** (의도적) |
| 우측 패널 | 핀 선택 시 상세 | 추천 목록 + 우선순위·완화·피드백 |
| 좌표 없는 주택 | 서버가 조회 단계에서 제외 | 목록에 남고 핀만 없음 |

---

## 2. 반드시 알아야 할 것 (함정 모음)

### 2-1. ID 세 가지를 구분한다

| 이름 | 정체 | 쓰는 곳 |
| --- | --- | --- |
| `housingId` / `houseId` | **`housing.id`** (둘은 같은 값) | 지도 핀 선택, 상세 조회, 추천 점수 조회, 피드백 |
| `noticeId` / `announcementId` | **`notice.id`** (둘은 같은 값) | **관심 공고 저장** |
| `housingSupplyId` | `housing_supply.id` | 상세의 공급 조건 카드 |

백엔드 `HouseSummaryResponse.from()` 과 `RecommendedHouseResponse` 가 둘 다 `Candidate.housingId()` 를
`houseId` 자리에 넣는다. 그래서 추천 결과의 `houseId` 로 지도 상세 API 를 그대로 부를 수 있다.

⚠️ **관심 저장에 `housingId` 를 넘기면 안 된다.** `InterestButton` 은 항상 notice id 를 받는다.
현재 넘기는 곳은 세 군데다 — `detail.noticeId`(지도 상세), `house.announcementId`(추천 목록),
`s.notice.noticeId`(캘린더).

### 2-2. 전체 공고 필터가 추천에 새면 안 된다

추천 모드에는 필터 UI 가 없다. 그래서 전체 공고 모드에서 고른 조건이 추천 요청에 섞이면
**사용자가 모르는 조건이 추천 결과를 몰래 좁힌다.** 화면에서 숨기는 것만으로는 부족하다.

```tsx
// MapPage.tsx — 추천 경로에는 항상 빈 필터를 넘긴다
const board = useRecommendationBoard(EMPTY_FILTER, canRecommend);
<RecommendationSidebar filter={EMPTY_FILTER} ... />   // RelaxSuggestions 도 같은 기준
```

추천 쪽에서 필터를 받는 경로는 **세 곳**이다. 하나라도 빠뜨리면 누수가 생긴다.
- `recommendationApi.list()` (`useRecommendationBoard`)
- `recommendationApi.relax()` (`RelaxSuggestions`)
- 사이드바로 내려가는 `filter` prop

### 2-3. `active = false` 는 "신청 불가"가 아니다

`Housing.active` / `HousingSupply.active` 는 **"최근 수집에서 원천 데이터에 보였는가"** 다.

```java
// NoticeSyncPersistenceService
MISSING_THRESHOLD = 2;   // 2회 연속 누락되면 active = false, 다시 보이면 true 로 복구
```

즉 비활성에는 두 가지가 섞인다. ① 실제로 공급이 빠짐 ② **수집 실패·첨부 파싱 실패로 못 읽음.**
수집이 수동 트리거라 ②가 드물지 않다.

그래서 정책이 이렇게 되어 있다.
- 공고가 유효(UPCOMING/OPEN)하고 주택이 활성이면 **공급 정보가 없어도 지도에 표시**한다
- 다만 상세에서 "공급 조건을 확인하지 못했어요" 안내 + 공고문 링크를 띄워 **신청 가능한 것처럼 보이지 않게** 한다
- 공급 조건 필터를 고르면 **활성 호실**만 본다 (비활성은 현재 유효한 조건이 아니다)

상세 API 가 활성 호실만 내려주므로(`findAllByHousingIdAndActiveTrueOrderByIdAsc`)
프론트에서 비활성을 걸러낼 필요는 없다. `detail.supplies` 가 비어 있으면 "활성 공급 없음"이다.

### 2-4. 공급 조건은 "같은 호실 하나"가 모두 만족해야 한다

주택 하나에 여러 공급 유형이 있을 때, 서로 다른 호실의 값을 조합하면 안 된다.

```
A형: 보증금 1,000만 · 26㎡      필터: 보증금 ≤ 2,500만 AND 면적 ≥ 30㎡
B형: 보증금 2,000만 · 36㎡   →  B형이 둘 다 만족 → 주택 포함 ✅

C형: 보증금 1,000만 · 20㎡      같은 필터
D형: 보증금 3,000만 · 40㎡   →  각각 하나씩만 만족 → 주택 제외 ✅
```

백엔드가 EXISTS 서브쿼리 안에서 AND 로 걸어 이를 보장한다.
실제 PostgreSQL 통합 테스트로 고정돼 있다 (`HousingPinQueryRepositoryIntegrationTest`).

### 2-5. 모집 상태는 교집합으로 좁힌다

공개 지도는 `UPCOMING`, `OPEN` 만 보여 준다. **기본값만 그렇게 두면 부족하다** —
`?statuses=CLOSED` 로 요청하면 마감 공고가 그대로 나가기 때문이다.

```java
// MapHousingFilterPolicy — 요청 상태 ∩ 공개 허용 상태
statuses=CLOSED       → 빈 집합 → 빈 결과  (조건을 빼면 '필터 없음'이 되어 전체가 나온다!)
statuses=OPEN,CLOSED  → {OPEN}
```

빈 집합을 `HousingPinQueryRepository.findPins()` 가 받으면 **쿼리 자체를 하지 않고** 빈 목록을 돌려준다.

### 2-6. 지도는 `includeUnknown` 을 무시한다

전체 목록(`/api/v1/houses`)과 추천은 `includeUnknown=true` 가 기본이라 "값이 없어도 통과"다.
**공개 지도만 반대로 엄격하다** — 고른 조건의 값이 없는 호실은 불충족으로 본다.
요청 파라미터로 `includeUnknown=true` 를 보내도 `MapHousingFilterPolicy` 가 `false` 로 덮는다.

고르지 **않은** 조건은 애초에 걸리지 않으므로, 값이 비어 있다는 이유로 주택이 사라지지는 않는다.

### 2-7. D-day 는 한국 시간 기준이다

접수 일정은 DB 에 `Instant` 로 들어 있고, DTO 에서 `LocalDate.ofInstant(..., Asia/Seoul)` 로 바꾼다.
UTC 로 자르면 날짜가 하루 밀린다 (예: `2026-10-15T14:30Z` = KST 10-16 이 아니라 10-15).

표시 규칙은 `utils/format.ts` 의 `applySchedule()` 한 곳에 있다.
- `UPCOMING` → 접수 시작일까지 (시작일이 없으면 마감일 기준)
- `OPEN` → 접수 마감일까지, 3일 이내면 강조
- 날짜 없음 → "접수 일정 미정" (**임의 날짜를 만들지 않는다**)

---

## 3. 코드 구조

### 백엔드 — 조회 계층이 둘이다

```
housing/repository/
  HouseFilterPredicates.java      필터 → QueryDSL 조건. 지도·목록·추천이 공유
    noticeScope(f)   공고·주택 기준: 상태 · 지역 · 기관 · 임대유형
    supplyScope(f)   공급 호실 기준: 보증금 · 월세 · 면적 · 주택유형
    all(f)           = active 2개 + 위 둘  (목록·추천이 쓰던 기존 조건과 동일)

  CandidateQueryRepository.java   호실 단위. 전체 목록 + 추천 후보
notice/repository/
  HousingPinQueryRepository.java  주택 단위. 지도 핀 (공급 조건은 EXISTS 안으로)
notice/dto/
  MapHousingFilterPolicy.java     지도 전용 정책 (상태 교집합 + 엄격 null)
```

**필터가 실제로 참조하는 위치** — 이름만 보면 틀린다.

| 필터 | 어디서 판정하나 |
| --- | --- |
| 공공임대 유형(`rentalTypes`) | **`notice`**.noticeType, 비면 notice.title 로 분류 |
| 주택 유형(`housingTypes`) | **`housing_supply`**.housingType 원본 문자열 (완전 일치) |
| 지역 | `housing`.provinceName / districtName |
| 보증금·월세·면적 | `housing_supply` (금액은 **원 단위**, 필터는 만 원 → ×10,000) |

### 프론트엔드 — 지도 주변

```
pages/MapPage.tsx                 모드·필터·선택 상태 조립 (URL 쿼리가 단일 출처)
features/map/
  MapPanel.tsx                    지도 인스턴스 생성·유지. 핀만 갈아 끼운다
  clustering.ts                   군집화. PinLayer { render, highlight, destroy } 계약
  kakaoMaps.ts                    SDK 로더 + 타입 (libraries=clusterer)
components/
  HousingDetail.tsx               공개 상세. 지도 패널과 /houses/:id 가 같은 컴포넌트를 쓴다
  map/RecommendationSidebar.tsx   추천 목록 + 우선순위·완화·피드백
hooks/
  useRecommendationBoard.ts       추천 상태 (preview + 즉시 재계산)
  useAuthGate.ts                  로그인/온보딩 팝업 + 로그인 후 이어서 할 작업
  useOnboardingStatus.ts          온보딩 완료 판정 (5가지 상태)
utils/supplyMatch.ts              "검색 조건 일치" 공급 유형 판정 (백엔드 복제)
```

### 왜 `HousingDetail` 을 공유하나

비회원과 회원이 보는 **기본 공고 정보가 달라지면 안 된다**는 정책 때문이다.
한 컴포넌트만 쓰면 구조적으로 어긋날 수 없다. 회원 전용 내용(추천 점수·근거)은
`score` 슬롯으로 주입한다.

### 추천 점수는 서버 것을 쓴다

`useRecommendationBoard` 는 `preview=true` 로 후보와 항목 점수를 함께 받아,
**우선순위 슬라이더를 움직일 때만** `utils/score.ts` 의 *서버와 같은 공식* 으로 다시 계산한다.
슬라이더를 건드리지 않으면 서버 순위와 같다. '적용'을 누르면 추천 기준에 저장된다.

⚠️ **프론트에서 새로운 추천 기준을 만들지 말 것.** 이 구조는 추천 담당자가 설계한 것이다.

---

## 4. 알려진 문제

### 🔴 P1 — Docker 없이 `./gradlew test` 를 돌리면 61건이 실패한다

**증상**

```
Docker 켜짐:  528 tests, 2 skipped, 0 failures
Docker 꺼짐:  528 tests, 2 skipped, 61 failures   ← 전부 ApplicationContext 로드 실패
```

PostgreSQL Testcontainers 통합 테스트가 **건너뛰어지지 않고 실패한다.** CI 에 Docker 가 없으면 빌드가 깨진다.

**원인** (확인함)

`PostgresRepositoryTestBase` 의 `@EnabledIf` 상속 자체는 정상 동작한다.
문제는 조건 함수다 — `DockerClientFactory.instance().isDockerAvailable()` 이
**데몬이 꺼져 있어도 `true` 를 돌려준다.** 중첩 클래스가 아닌
`CandidateQueryRepositoryIntegrationTest` 도 29건 전부 실행·실패한 것이 근거다.

**수정 방향**

`src/test/java/com/zipick/support/DockerAvailability.java` 의 `probe()` 를
"조회"가 아니라 **실제 연결 시도**로 바꾼다.

```java
private static boolean probe() {
    try {
        DockerClientFactory.instance().client();   // 연결 안 되면 여기서 예외
        return true;
    } catch (Throwable ignored) {
        return false;
    }
}
```

고친 뒤 **Docker 를 끈 상태로** `./gradlew cleanTest test` 를 돌려
`failures=0, skipped=63` 이 나오는지 반드시 확인할 것 (`test` 태스크는 캐시되므로 `cleanTest` 필수).

### 🟡 P2 — `utils/supplyMatch.ts` 가 백엔드 판정을 복제하고 있다

상세 패널에서 "검색 조건 일치" 배지를 띄우려고 서버 필터 로직을 프론트에 따라 적었다.
**서버 규칙이 바뀌면 양쪽을 같이 고쳐야 한다.** 파일 주석에 대응표를 남겨 뒀다.

근본 해결은 백엔드가 상세 응답에 "이 호실이 조건을 만족하는지" 플래그를 실어 주는 것이지만,
DTO 변경이라 담당자 승인이 필요해 하지 않았다.

### 🟡 P3 — 프론트엔드에 테스트가 없다

`package.json` 의 `lint` 는 `tsc --noEmit` 이고 테스트 러너가 없다.
검증 수단은 타입 검사와 빌드뿐이다.

### 🟢 P4 — 미사용 코드

- `api/house.ts` 의 `houseApi.list()` — 지도가 전용 API 로 옮겨 가 쓰이지 않는다.
  같은 파일의 `filterOptions()` 는 필터 칩·온보딩에서 계속 쓰므로 **파일은 지우지 말 것.**
- 백엔드 `MapHousingQueryService.findOpenHousings()` — 정책 원복용으로 남겨 둔 것.

---

## 5. 아직 안 된 것

### 백엔드 (승인 전이라 손대지 않음)

| 항목 | 왜 필요한가 | 범위 |
| --- | --- | --- |
| `MapHousingSummaryResponse` 에 `province`·`district` 추가 | 행정구역별 클러스터링의 전제. 주소 문자열 파싱은 부정확하다 | DTO 2필드. `Housing` 에 이미 있는 값이라 쿼리 변경 불필요 |
| 좌표 없는 주택 집계 | 전체 공고 모드에서 "지도에 안 뜨는 N곳"을 안내할 수 없다 (서버가 조회 단계에서 빼므로 개수를 모른다) | 집계 엔드포인트 또는 응답에 카운트 추가 |
| 상세 응답의 "조건 일치" 플래그 | P2 해소 | `MapHousingSupplyResponse` 에 1필드 |

### 프론트엔드

- **행정구역별 클러스터링** — 계획·수정 지점은 [`docs/map-clustering.md`](./map-clustering.md) 에 정리.
  `clustering.ts` 의 `TODO(행정구역별 클러스터링)` 주석이 이 문서를 가리킨다.
  `PinLayer` 계약만 지키면 `clustering.ts` 하나만 갈아 끼우면 된다.
- **군집 파라미터 튜닝** — `minLevel: 6`, `minClusterSize: 2` 는 임의값이다. 실데이터로 조정 필요.
- **브라우저 수동 검증** — 지금까지 타입 검사·빌드·모듈 로드까지만 확인했다. 아래 6장 참고.

---

## 6. 아직 하지 않은 브라우저 검증

**이 영역은 실제 화면에서 한 번도 통과시킨 적이 없다.** 이어받는 사람이 가장 먼저 할 일이다.

**전체 공고**
- [ ] `/` → `/map` 이동, 핀 표시, 군집 숫자가 주택 수와 맞는지
- [ ] 필터 변경 시 핀 갱신 + **지도 중심·확대가 유지되는지** (전국으로 튕기면 안 됨)
- [ ] 필터를 바꿔도 `?housing=`·`?mode=` 가 풀리지 않는지
- [ ] 공고중(UPCOMING) 공고의 상세와 **사진이 깨지지 않는지**

**추천 모드**
- [ ] 비회원 → 로그인 팝업 / 온보딩 미완료 → 조건 입력 팝업
- [ ] 우선순위 슬라이더 → 목록·핀 동시 갱신, '적용' 후 새로고침해도 유지
- [ ] 조건 완화 제안 · "별로예요" 피드백 후 목록·핀 갱신
- [ ] **전체 모드에서 필터를 걸고 추천으로 전환했을 때 추천 결과가 좁아지지 않는지** (2-2 항목)

**상세·인증**
- [ ] 활성 공급 없는 주택 → "공급 조건을 확인하지 못했어요" + 공고문 링크
- [ ] 공급 필터 적용 후 → "검색 조건 일치" 배지가 맞는 유형에 붙는지
- [ ] 좌표 없는 추천 주택 → 목록에 남고 상세는 열리며 로드뷰 대신 안내
- [ ] 비회원 ☆ → 팝업 → 가입/로그인 → **같은 핀·같은 공고로 복귀 + 저장**
- [ ] 이미 저장한 공고로 반복 → 중복·해제 없음 (서버 `save()` 는 멱등)
- [ ] D-day: UPCOMING=시작일, OPEN=마감일, 일정 없으면 "미정"

**모바일(~400px)**
- [ ] 헤더 접힘, 필터 드롭다운, 지도/패널 세로 배치

---

## 7. 실행·검증

```bash
# 백엔드 (DB_USERNAME / DB_PASSWORD / JWT_SECRET 환경 변수 필요)
cd zipick-backend && ./gradlew bootRun

# 프론트엔드 (http://localhost:5173, /api → 8080 프록시)
cd zipick-frontend && npm run dev

# 검증
cd zipick-frontend && npx tsc --noEmit && npm run build
cd zipick-backend  && ./gradlew test        # 통합 테스트는 Docker 필요 (4장 P1 참고)
```

외부 API 키는 `zipick-backend/config/application-secret.yml` 에 둔다 (`.gitignore` 대상).
지도에는 `zipick-frontend/.env.local` 의 `VITE_KAKAO_MAP_KEY` 가 필요하다.

---

## 8. 다른 담당자 코드를 건드린 부분과 원복 방법

지도·공고 담당 영역과 추천 담당 영역을 수정했다. 정책이 바뀌면 아래대로 되돌릴 수 있다.

| 변경 | 목적 | 원복 |
| --- | --- | --- |
| `NoticeStatus.PUBLIC_STATUSES` | 공개 범위를 UPCOMING+OPEN 으로 | `Set.of(OPEN)` 으로 — **한 줄**. 목록·상세·이미지가 함께 되돌아간다 |
| `MapHousingAssetService.findVisibleHousing` | UPCOMING 이미지·첨부 허용 | 조건을 `== NoticeStatus.OPEN` 으로 (위와 독립) |
| `MapHousingDetailResponse` | 접수 일정 D-day | 필드 2개 + `toSeoulDate()` 제거 |
| `MapHousingController.list()` | 지도 필터 지원 | 파라미터 제거 + `service.findOpenHousings()` 호출로 복귀 |
| `CandidateQueryRepository.build()` | 판정식 공유 | `HouseFilterPredicates.all()` 본문을 그대로 되돌린다. **변경 전 원본이 테스트의 `LegacyCandidateFilterFixture` 에 그대로 보존되어 있다** |

**추천 회귀 안전장치** — `HouseFilterPredicatesEquivalenceTest` 가 변경 전/후 조건을 21가지 필터 조합에서,
`CandidateQueryRepositoryIntegrationTest` 가 실제 PostgreSQL 조회 결과를 13가지 조합에서 비교한다.
필터 로직을 손볼 때 이 테스트가 깨지면 **추천 결과가 바뀐다는 뜻**이다.

---

## 9. 손대면 안 되는 것

- 추천 점수 계산·순위 규칙 (`ScoreCalculator`, `RecommendationRanker`) — 추천 담당 영역
- 공고 수집 파이프라인 (`NoticeSyncPersistenceService` 등) — 공고 담당 영역
- 엔티티·DDL — 변경하려면 팀 공유와 로컬 DB 재적용이 필요하다
- 인증 토큰 구조 (`api/client.ts` 의 single-flight 재발급, 메모리 Access Token)
- **추천 모드에 기본 필터를 다시 붙이는 것** — 저장된 희망조건과 중복돼 혼란스럽다는 팀 결정이다
