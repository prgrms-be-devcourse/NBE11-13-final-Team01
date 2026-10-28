# 공급주택 주변 생활 인프라

공급주택(`housing`)의 좌표를 기준으로 카카오 로컬 **카테고리 장소 검색**을 호출해 주변 생활시설을 수집하고,
지도 상세 화면에서 카테고리별 최근접 시설 1곳과 반경 내 전체 시설 수를 보여 준다.

기준 데이터는 `notice` → `housing` → `housing_supply` 구조이며, 위치 정보를 가진 **`housing`에 연결**한다.

---

## 데이터 흐름

```
[수집]  서버 내부 배치. 외부에서 호출할 수 있는 엔드포인트가 없다.

  (수동) 공고 수집 → notice / housing 저장 → 주소 → 좌표 보완
                                 ↓ 좌표가 채워진 주택만
  InfraCollectionScheduler   기동 10초 뒤 1회 · 기동 후 3시간 동안 10분 간격 보정 · 이후 하루 1회
      │
      └ 대상이 없어질 때까지 batch(100개)를 대기 없이 이어서 처리한다
            ├ 수집 대상 조회   ①스냅샷 없음 ②TTL 만료 ③정책 변경 ④좌표 변경
            │                 (커서 = 마지막으로 처리한 housing_id 보다 큰 것만)
            ├ 카카오 호출      주택 1채당 카테고리 수만큼 (현재 6회)
            └ 저장            housing_infra_snapshot + housing_infra_place

[조회]  카카오를 호출하지 않는다. DB에 저장된 값만 읽는다.

  GET /api/v1/map/housings/{housingId}/infra
      └ 상태 판정(FRESH / STALE / PENDING / UNAVAILABLE) → 카테고리 요약 반환

[화면]  MapPage 주택 상세 패널. 공급주택 상세와 별도 요청으로 병렬 조회한다.
```

수집 경로와 조회 경로가 완전히 분리돼 있다. 사용자 요청이 외부 API 지연에 묶이지 않고,
추천 알고리즘도 나중에 외부 호출 없이 DB만 읽으면 된다.

---

## 주요 정책

| 항목 | 정책 |
| --- | --- |
| 수집 시점 | **사용자 조회 시점이 아니라 서버 배치가 미리 수집한다.** 조회 중 외부 호출 없음 |
| 수집 주기 | ①기동 10초 뒤 1회 ②기동 후 3시간 동안 10분 간격 보정 ③이후 **하루 1회 cron**. 셋 다 같은 로직이며 `ReentrantLock`으로 겹치지 않는다 |
| 한 번의 실행 | **대상이 없어질 때까지** `batch-size`(100)씩 대기 없이 이어서 처리한다. batch 사이 대기가 없어도 순차 호출이라 초당 호출 속도는 변하지 않는다 |
| 중복 처리 방지 | 커서(마지막으로 처리한 `housing_id`) 페이징. **한 실행 안에서 같은 주택을 두 번 집지 않는다.** 실패한 주택은 저장되지 않아 조건을 계속 만족하므로, 커서가 없으면 같은 batch 가 무한히 반복된다 |
| 수집 대상 | **공고 예정(`UPCOMING`) + 접수 중(`OPEN`)** + `active` + 좌표가 있는 주택. 추천 후보 범위와 같은 집합을 쓴다 |
| 재수집 조건 | ①스냅샷 없음 ②TTL 30일 만료 ③수집 정책 변경 ④주택 좌표 변경 |
| 캐시 TTL | 30일. 유효한 캐시는 다시 수집하지 않는다 (**서버를 재시작해도 재수집하지 않음**) |
| 저장 범위 | 카테고리당 **가장 가까운 시설 1곳만** 저장한다 |
| 전체 시설 수 | 저장한 행 수가 아니라 카카오 `meta.total_count`(반경 내 전체 수) |
| 부분 실패 | 6개 카테고리가 **모두 성공했을 때만** 저장한다. 하나라도 실패하면 저장하지 않는다 |
| 0건 처리 | 결과가 0건인 카테고리는 시설 행을 만들지 않고, 화면에도 표시하지 않는다 |
| 오류 ≠ 0건 | API 오류·타임아웃·응답 파싱 실패를 정상적인 0건으로 처리하지 않는다 |
| 갱신 실패 | **기존 캐시를 지우거나 덮어쓰지 않는다.** 다음 실행에 다시 시도한다(같은 실행 안에서는 재시도하지 않는다) |
| 장애 구분 | 통신 실패가 연속 3회면 남은 batch 가 있어도 **실행 전체를 중단**한다. 특정 주택의 데이터 문제는 그 주택만 건너뛴다 |
| 거리 | 카카오가 주는 **직선거리**다. 실제 도보 경로·도보시간이 아니며 화면에 그렇게 안내한다 |
| 반경 변경 | 사용자가 반경·카테고리를 지정하는 기능은 제공하지 않는다. 서버 설정으로만 정한다 |
| 수동 수집 | **수동 수집 API를 만들지 않았다.** 외부에서 임의로 반복 실행할 경로가 없다 |

> **핵심 규칙:** 스냅샷 행이 존재한다 = 해당 정책의 모든 카테고리를 정상 조회했다.
> 그래서 시설 행이 없는 카테고리는 '수집 실패'가 아니라 **확실한 0건**으로 읽을 수 있다.

### 수집 카테고리

| 코드 | 카카오 코드 | 라벨 | 반경 |
| --- | --- | --- | --- |
| `SUBWAY` | `SW8` | 지하철역 | 1,500m |
| `MART` | `MT1` | 대형마트 | 1,500m |
| `CONVENIENCE_STORE` | `CS2` | 편의점 | 1,500m |
| `HOSPITAL` | `HP8` | 병원 | 1,500m |
| `PHARMACY` | `PM9` | 약국 | 1,500m |
| `SCHOOL` | `SC4` | 학교 | 1,500m |

반경은 추천의 `infra-zero-score-m`(1,500m)과 맞췄다. 더 좁게 잡으면 반경 밖 시설이
'없음'(0점)으로 떨어져 점수가 불연속이 된다(아래 참고).

`application.yml`의 `zipick.infra.categories`로 관리한다. 화면 표시 순서도 이 순서를 따른다.
**값을 바꾸면 `policy_signature`가 달라져 다음 배치에서 전체가 자동 재수집된다.**

은행(`BK9`)·카페(`CE7`)는 수집하지 않는다. 추천의 `categoryName()`은 이 둘도 알고 있으므로,
사용자가 인프라 가중치에 넣으면 '없음'(0점)으로 계산된다. 가중치 입력 UI를 만들 때 6종만 노출해야 한다.

### 수집 실행 설정

`application.yml`의 `zipick.infra.collection`이다.

| 키 | 기본값 | 뜻 |
| --- | --- | --- |
| `enabled` | `true` | 끄면 세 진입점 모두 아무것도 하지 않는다 |
| `startup-delay` | `PT10S` | 기동 후 최초 수집까지의 지연 |
| `catch-up-interval` | `PT10M` | 기동 수집 이후 보정 실행 간격 |
| `catch-up-window` | `PT3H` | 보정 실행을 유지하는 기간(기동 시점 기준). 지나면 일일 cron 만 남는다. 값의 근거는 아래 **왜 보정 실행이 필요한가** 참고 |
| `daily-cron` | `0 30 4 * * *` | 하루 1회 정기 수집 시각. **공고 수집 스케줄이 정해지면 그 뒤 시각으로 맞춘다** |
| `cron-zone` | `Asia/Seoul` | cron 해석 기준 시간대 |
| `batch-size` | `100` | 한 번에 DB 에서 가져올 최대 주택 수. 대상이 남아 있으면 대기 없이 다음 batch 를 처리한다 |
| `api-failure-threshold` | `3` | 카카오 호출이 연속 이 횟수만큼 실패하면 실행 전체를 중단한다 |

> ⚠️ `spring.task.scheduling.pool-size: 2`가 함께 필요하다. 기본값은 1이고, 스레드가 하나면
> 수십 분 걸리는 인프라 수집이 도는 동안 알림 스케줄러가 큐에서 대기한다.
> **유실은 아니다**(마감 알림은 `dedupeKey`, 새 공고는 커서로 다음 실행에 처리된다). 다만 지연이
> 기존 약 3.5분에서 **약 50분까지 커진다.** 스레드를 늘려도 인프라 쪽 두 진입점은 `ReentrantLock`
> 으로 직렬화되므로 동시 수집은 생기지 않는다.

---

## 테이블

### `housing_infra_snapshot` — 주택당 1행

| 필드 | 설명 |
| --- | --- |
| `housing_id` | **`housing.housing_id` FK + UNIQUE.** 다른 기능에서 조인할 때 쓰는 키 |
| `policy_signature` | 수집 당시 카테고리·반경을 적은 평문. 예: `MART:MT1:1000\|SUBWAY:SW8:1500` |
| `collected_latitude` / `collected_longitude` | 수집 당시 주택 좌표. 현재 좌표와 다르면 재수집 |
| `nearest` | `jsonb`. 카테고리별 최근접 요약 (아래 참고) |
| `collected_at` | 수집 시각. TTL 판정 기준 |

### `housing_infra_place` — 카테고리당 0행 또는 1행

| 필드 | 설명 |
| --- | --- |
| `housing_infra_snapshot_id` | 스냅샷 FK. `(snapshot_id, category_code)` UNIQUE |
| `category_code` | 서비스 카테고리 코드 (`SUBWAY` 등) |
| `search_count` | **반경 내 전체 시설 수.** 카카오 `meta.total_count` |
| `name` / `distance_meter` | 최근접 시설명 / 직선거리(m) |
| `kakao_place_id`, `road_address`, `address`, `latitude`, `longitude`, `place_url` | 현재 화면에서 쓰지 않지만 확장용으로 저장해 둔다 |

JSON 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` + `jsonb`로 매핑한다.
**`@Lob`을 쓰면 PostgreSQL에서 large object(`oid`)가 되므로 쓰지 않는다.**

---

## 조회 API

```http
GET /api/v1/map/housings/{housingId}/infra
```

- 비로그인 조회 가능. 쿼리 파라미터 없음.
- 공급주택 상세(`GET /api/v1/map/housings/{housingId}`)와 **합치지 않고 별도 호출**한다.

```json
{
  "success": true,
  "data": {
    "housingId": 101,
    "status": "FRESH",
    "collectedAt": "2026-10-19T04:12:00Z",
    "distanceBasis": "STRAIGHT_LINE",
    "categories": [
      { "code": "SUBWAY", "label": "지하철역", "radiusMeter": 1500,
        "nearestName": "신설동역", "nearestDistanceMeter": 683, "totalCount": 6 }
    ]
  }
}
```

| `status` | 의미 | 화면 |
| --- | --- | --- |
| `FRESH` | 유효한 수집 결과 | 목록 표시. 비어 있으면 "반경 안에 시설이 없습니다" |
| `STALE` | 이전 결과는 있으나 갱신 대기 중 | 이전 값 표시 + "이전에 수집한 정보" 안내 |
| `PENDING` | 좌표는 있으나 아직 수집 전 | "주변 인프라 정보를 준비 중입니다" |
| `UNAVAILABLE` | 좌표가 없어 제공 불가 | "위치 정보가 확인되지 않았습니다" |

`FRESH` + 빈 목록은 **정상 수집했고 주변에 시설이 없음**을 뜻한다. 수집 실패(`PENDING`)와 구분된다.
주택이 없거나 모집 중이 아니면 `404 HOUSE_NOT_FOUND`다.

---

## 다른 기능과의 연결점

### 1. 추천 알고리즘 — 연결 완료

추천의 확장 지점 `com.zipick.housing.infra.InfraDistanceProvider`를
`SnapshotInfraDistanceProvider`(`@Primary`)가 구현한다. **추천 코드는 수정하지 않았다.**

```
RecommendationService.scoreAll()
  └ infraProvider.nearest(candidates)          // @Primary 로 NoInfraDistanceProvider 를 대체
       └ 후보를 housingId 단위로 줄여 findAllByHousingIdIn() 한 번
       └ nearest jsonb → {"SW8": 683, "MT1": null, ...}  // 수집 대상 6종을 모두 담는다
  └ ScoreCalculator.scoreInfra(ctx, nearest.get(housingId))
```

- 추천 요청 중에 **카카오를 호출하지 않는다.** 배치가 채워 둔 DB만 읽는다.
- 좌표는 `Candidate.lat()`/`lng()`에 이미 있어 `housing`을 다시 읽지 않는다.

**스냅샷을 쓰는 조건**

| 상황 | 추천에 사용 | 이유 |
| --- | --- | --- |
| TTL(30일) 만료 | **사용** | 시설 위치는 잘 바뀌지 않는다. 오래됐을 뿐 같은 위치·같은 반경의 결과다 |
| 수집 정책 변경 | 제외 | 다른 반경으로 잰 값이라 지금 기준과 맞지 않는다 |
| 주택 좌표 변경 | 제외 | 다른 위치를 잰 값이다 |

판정은 `HousingInfraSnapshot.matchesCollectionContext()`가 한다.
조회 API의 `FRESH`/`STALE` 판정(`isValid()`)은 여기에 TTL 조건을 더한 것이다.

**인프라 점수 계산 규약 (2026-10-19 개선)**

Provider 가 돌려주는 Map 은 세 상태를 구분한다. `ScoreCalculator.scoreInfra()` 가 이 규약에 기댄다.

| 상태 | Map 표현 | 추천 처리 |
| --- | --- | --- |
| 수집 전 / 쓸 수 없는 데이터 | **주택 id key 없음** | 계산 제외 → 인프라 가중치를 다른 항목에 재분배 |
| 조회했으나 반경 내 0건 | key 있고 **값이 `null`** | **0점**으로 센다 |
| 시설 있음 | key + 거리(m) | 거리로 점수 |

수집한 주택은 **카테고리 6종을 모두 key 로** 담는다. 그래서

- 주변이 전부 0건인 집은 '정보 없음'이 아니라 **인프라 0점**을 받는다.
- 사용자 가중치가 없으면 **6종 전체를 같은 비중**으로 본다. 일부만 검색된 집은 나머지가 0점으로 들어간다.
- 사용자 가중치가 있으면 기존대로 **지정한 카테고리만** 본다.

> ⚠️ **이 규약 전에는** `nearest` 에 검색된 카테고리만 들어 있어, 편의점 하나만 가까운 집도
> 인프라 만점을 받았다. 지금은 6종 기준으로 평가하므로 **인프라 점수가 전반적으로 낮아진다.**
> 또한 인프라 0점이 실제로 나올 수 있게 되면서, 사용자가 인프라를 '꼭' 조건으로 두면
> 그 주택이 추천에서 제외될 수 있다(기존 MUST 규칙).

**거리 점수의 경계**

반경(1,500m)과 추천의 `infra-zero-score-m`(1,500)이 맞춰져 있어 "1,500m에서 0점"과
"1,500m 밖은 없음(0점)"이 이어진다. **둘 중 하나만 바꾸면 점수가 불연속이 된다.**

### 2. 공고·공급주택 수집 — 실행 순서

인프라 수집은 공고 수집과 **코드로 연결돼 있지 않다.** `MyHomeSyncService`를 수정하지 않았다.
대신 수집 대상 조건에 `좌표가 있을 것`이 들어 있어, 좌표 보완이 끝난 주택만 자동으로 대상이 된다.

```
공고 수집 (NoticeCollectionApplication 또는 POST /api/internal/notices/sync)
  → 웹 서버 기동 → 기동 수집이 좌표가 채워진 주택을 전부 처리한다
```

- `NoticeCollectionApplication`은 웹 서버 없이 뜨므로 **인프라 스케줄러가 실행되지 않는다.**
- 공고 수집 과정에서 좌표를 얻지 못한 주택은 인프라도 수집되지 않는다(`UNAVAILABLE`).
  **지오코딩 성공률이 곧 인프라 제공률이다.**
- **수집 범위와 지도 노출 범위는 다르다.** 수집은 `UPCOMING`+`OPEN`(추천 후보와 동일),
  지도 상세 조회 API 는 `OPEN`만 반환한다. `UPCOMING` 주택의 인프라는 추천 점수에만 쓰인다.

**왜 보정 실행이 필요한가**

두 수집기는 서로를 모른다. 기동 직후 공고 수집과 인프라 수집이 **각자 돌 수 있고**,
인프라 기동 수집이 먼저 끝나면 그 뒤에 좌표가 채워진 주택은 **다음 날 cron 까지 인프라 없이 남는다.**
그 동안 그 주택은 조회 API 에서 `PENDING`(수집 대기)으로 내려가고, 추천에서는
**인프라 항목 계산에서 빠진 채 그 가중치가 다른 항목으로 재분배된다**(0점으로 깎이는 것이 아니다 —
`InfraDistanceProvider` 규약상 스냅샷이 없는 주택은 Map 에 key 자체가 없다).
그래서 기동 후 `catch-up-window`(기본 3시간) 동안 `catch-up-interval`(기본 10분) 간격으로 다시 확인한다.

**왜 3시간인가.** 좌표는 공고 수집(`MyHomeSyncService.synchronize()`)의 **맨 마지막 단계**에서 생기므로,
이 창이 덮어야 하는 것은 지오코딩 시간이 아니라 공고 수집 **전체** 시간이다.

| | 값 |
| --- | --- |
| 실측 조건 | AI 전체 활성화(`GEMINI_PDF_PARSER_ENABLED=true`, `GEMINI_NORMALIZATION_ENABLED=true`) + **빈 DB 최초 전건 수집** |
| 수집량 | 신규 공고 110건 / 주택 369건 / 공급정보 674건 / 지오코딩 369건(갱신 339, 미해결 30) |
| 소요시간 | **2시간 21분 25초** (`elapsedSeconds=8485`), 정상 종료 |

- 보정 실행이 10분 간격이므로, 같은 조건이면 **약 2시간 30분 시점의 실행**에서 새 좌표를 포착한다.
- `PT3H`는 이 실측을 수용하면서 no-op polling 도 과도하게 늘리지 않는 **임시 안전값**이다.
  창을 늘리는 비용은 대상이 0건일 때 인덱스를 타는 SELECT 몇 번뿐이라 비대칭이 분명하다.
- 위 수치는 **빈 DB + AI cold start + 전체 지오코딩**이 겹친 평상시보다 긴 케이스다.
  **공고 수집기의 기동·일일 스케줄이 확정되면 실제 cron 시각과 증분 수집 시간을 기준으로 다시 조정한다.**

> 공고 수집을 평소보다 늦게(기동 3시간이 지난 뒤) 수동 실행했다면 그 주택들은 다음 일일 cron 에서 수집된다.
> 급하면 백엔드를 재시작해 기동 수집을 다시 태우면 된다. 이미 수집된 주택은 다시 호출하지 않는다.

**수집 규모 (2026-10-20 기준)**

| | OPEN | UPCOMING | 합계 |
| --- | ---: | ---: | ---: |
| 좌표가 있는 주택 | 1,201 | 187 | **1,388** |

전건 재수집 시 1,388 × 6 = **약 8,328회** 호출된다(카카오 로컬 API 기능별 일일 쿼터 100,000의 약 8%).
기동 수집 한 번으로 전부 채워지며 **약 50분**이 걸린다(순차 호출 속도가 하한을 정한다).
batch 사이 대기를 없애도 초당 호출 속도는 그대로이므로 간격을 조절하는 운영 절차는 더 이상 필요 없다.

### 3. 프론트 — 상태 4값

`FRESH` / `STALE` / `PENDING` / `UNAVAILABLE`이 화면 분기의 계약이다.
다른 화면에서 인프라를 쓸 때도 `PENDING`(수집 대기)과 `UNAVAILABLE`(제공 불가)을 구분해 안내한다.

### 4. 카카오 로컬 API — 주소 검색과 공유하는 설정

주소 → 좌표 변환(`KakaoAddressClient`)과 **같은 REST 키, 같은 `kakaoLocalRestClient` 빈**을 쓴다.
`config/application-secret.yml`의 `kakao.local.rest-api-key` 하나로 둘 다 동작한다. 새 환경 변수는 없다.

단, **일일 쿼터는 API별로 따로 집계된다.** 주소 검색 사용량과 카테고리 검색 사용량은 별개다.
로컬 API 기능별 일간 쿼터는 100,000건이고, 초당·분당 제한은 존재하지만 수치가 공개돼 있지 않다.
수집은 완전히 순차 실행이라(스케줄러 스레드 1개 → 주택 순차 → 카테고리 6회 순차) 동시 호출이 없다.

### 타임아웃

`kakaoLocalRestClient`에는 **connect 3초 / read 10초**를 건다 (`KakaoLocalApiConfig`).

- 타임아웃이 없으면 카카오가 응답하지 않을 때 수집 스레드가 무한 대기하고,
  `ReentrantLock`을 쥔 채로 멈춰 **다음 회차까지 전부 건너뛴다.** 로그도 남지 않는다.
- ⚠️ **`RestClient.builder()`를 직접 호출하면 `spring.http.clients.*` 전역 설정이 적용되지 않는다.**
  그래서 요청 팩토리(`JdkClientHttpRequestFactory`)를 직접 만들어 붙였다.
  전역 설정을 쓰지 않은 이유는 공고 수집·슬랙 알림은 응답 특성이 달라 같은 값을 강요할 수 없기 때문이다.
- read 를 10초로 넉넉히 둔 이유: 이 클라이언트를 함께 쓰는 좌표 변환(`HousingGeocodingService`)은
  **호출이 한 번만 실패해도 그 회차 전체를 중단**한다. 일시적 지연을 실패로 오해하면 손해가 크다.
  인프라 수집은 연속 3회 실패해야 멈추므로 최악이어도 30초면 빠져나온다.

---

## 카카오 카테고리 장소 검색 필드

- 요청 주소: `GET https://dapi.kakao.com/v2/local/search/category.json`
- 헤더: `Authorization: KakaoAK {REST_API_KEY}`

### 요청 파라미터

| 필드 | ZIPICK 사용값 | 의미 |
| --- | --- | --- |
| `category_group_code` | `SW8` 등 | 카테고리 그룹 코드 |
| `x` / `y` | 주택 경도 / 위도 | 중심 좌표. **둘을 넘겨야 응답에 `distance`가 포함된다** |
| `radius` | 1500 | 검색 반경(m). 허용 범위 0~20,000 |
| `sort` | `distance` | 거리순. 사용하려면 `x`·`y`가 필요하다 |
| `size` | `1` | 페이지당 개수. 허용 범위 1~15 |
| `page` | (미사용) | 페이지 번호. 허용 범위 1~45 |

### 응답 — `meta`

| 필드 | 의미 |
| --- | --- |
| `total_count` | **반경 내 전체 검색 결과 수. ZIPICK이 쓰는 값이다** |
| `pageable_count` | 조회 가능한 문서 수. **최대 45에서 잘리므로 개수 표시에 쓰면 안 된다** |
| `is_end` | 마지막 페이지 여부 |

`size=1`로 요청해도 `meta`는 그대로 내려온다. 그래서 **호출 1회로 최근접 1곳과 전체 개수를 함께 얻는다.**

### 응답 — `documents[]`

| 필드 | 의미 | ZIPICK 저장 |
| --- | --- | --- |
| `place_name` | 장소명 | `name` |
| `distance` | 중심 좌표로부터의 직선거리(m) | `distance_meter` |
| `id` | 카카오 장소 ID | `kakao_place_id` |
| `road_address_name` / `address_name` | 도로명 / 지번 주소 | `road_address` / `address` |
| `x` / `y` | 경도 / 위도 | `longitude` / `latitude` |
| `place_url` | 카카오맵 상세 URL | `place_url` |
| `category_name`, `category_group_name`, `phone` | 분류명, 전화번호 | 저장하지 않음 |

> 카카오맵에 등록된 장소 기준이라 일부 시설이 누락될 수 있다. 개수는 참고값으로 본다.

---

## 로컬 적용

```bash
psql -U {사용자명} -d zipick -f zipick-backend/db/infra-init.sql
```

기존 DB에 이 파일만 한 번 실행하면 된다. 인프라 테이블 2개만 DROP/CREATE 하므로 공고·사용자 데이터에는 영향이 없다.
DB를 새로 만들 때는 `notice-init.sql` → `local-init.sql` → **`infra-init.sql`** 순서다
(`housing`을 참조하므로 반드시 마지막).

카카오 REST 키가 없으면 수집이 자동으로 꺼지고 로그만 남는다. 앱은 정상 동작하며 화면에는 `PENDING`이 표시된다.
수집을 끄려면 `zipick.infra.collection.enabled: false`.

### 수집 확인

```sql
select count(*) from housing_infra_snapshot;
select distinct policy_signature from housing_infra_snapshot;   -- 한 줄만 나오면 정상
select s.housing_id, p.category_code, p.name, p.distance_meter, p.search_count
  from housing_infra_snapshot s
  join housing_infra_place p on p.housing_infra_snapshot_id = s.housing_infra_snapshot_id
 order by s.housing_id, p.distance_meter;
```

로그는 batch 하나를 끝낼 때마다, 그리고 실행이 끝날 때 남는다. 대상이 0건이면 아무 로그도 남지 않는다.

```
주변 인프라 배치 완료: batch=1, 대상=100, 저장=100, 누적대상=100, 누적저장=100
주변 인프라 배치 완료: batch=2, 대상=100, 저장=99, 누적대상=200, 누적저장=199
...
주변 인프라 수집 완료: batch=14, 대상=1388, 저장=1385, 데이터오류=3, 호출실패=0, 카카오 호출=8322, 중단=false, elapsedSeconds=2914
```

- 배치 로그는 **진행 상황**이다. 전건 수집은 수십 분 걸리므로, 이 줄이 계속 늘어나면 정상 진행 중이다.
- `batch` 는 100개씩 몇 번에 나눠 가져왔는지다. 한 번의 실행에서 대상을 모두 소진하므로
  완료 로그가 남으면 그 시점의 대상이 전부 처리된 것이다(`중단=true` 가 아닌 한).
- `elapsedSeconds` 는 **실제 수집에 걸린 시간**이다. 기동 지연(`startup-delay`)은 들어 있지 않고,
  batch 사이에 별도 대기가 없으므로 순수한 호출·저장 시간이다.
