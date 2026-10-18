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
  InfraCollectionScheduler   기동 10초 뒤 첫 실행, 이후 5분 간격
      ├ 수집 대상 조회   ①스냅샷 없음 ②TTL 만료 ③정책 변경 ④좌표 변경
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
| 수집 주기 | 기동 10초 뒤 첫 실행, 이후 5분 간격(`fixedDelay`, 겹치지 않음) |
| 수집 대상 | 모집 중(`OPEN`) + `active` + 좌표가 있는 주택 |
| 재수집 조건 | ①스냅샷 없음 ②TTL 30일 만료 ③수집 정책 변경 ④주택 좌표 변경 |
| 캐시 TTL | 30일. 유효한 캐시는 다시 수집하지 않는다 (**서버를 재시작해도 재수집하지 않음**) |
| 저장 범위 | 카테고리당 **가장 가까운 시설 1곳만** 저장한다 |
| 전체 시설 수 | 저장한 행 수가 아니라 카카오 `meta.total_count`(반경 내 전체 수) |
| 부분 실패 | 6개 카테고리가 **모두 성공했을 때만** 저장한다. 하나라도 실패하면 저장하지 않는다 |
| 0건 처리 | 결과가 0건인 카테고리는 시설 행을 만들지 않고, 화면에도 표시하지 않는다 |
| 오류 ≠ 0건 | API 오류·타임아웃·응답 파싱 실패를 정상적인 0건으로 처리하지 않는다 |
| 갱신 실패 | **기존 캐시를 지우거나 덮어쓰지 않는다.** 다음 주기에 다시 시도한다 |
| 장애 구분 | 통신 실패가 연속 3회면 회차 중단. 특정 주택의 데이터 문제는 그 주택만 건너뛴다 |
| 거리 | 카카오가 주는 **직선거리**다. 실제 도보 경로·도보시간이 아니며 화면에 그렇게 안내한다 |
| 반경 변경 | 사용자가 반경·카테고리를 지정하는 기능은 제공하지 않는다. 서버 설정으로만 정한다 |
| 수동 수집 | **수동 수집 API를 만들지 않았다.** 외부에서 임의로 반복 실행할 경로가 없다 |

> **핵심 규칙:** 스냅샷 행이 존재한다 = 해당 정책의 모든 카테고리를 정상 조회했다.
> 그래서 시설 행이 없는 카테고리는 '수집 실패'가 아니라 **확실한 0건**으로 읽을 수 있다.

### 수집 카테고리

| 코드 | 카카오 코드 | 라벨 | 반경 |
| --- | --- | --- | --- |
| `SUBWAY` | `SW8` | 지하철역 | 1,500m |
| `MART` | `MT1` | 대형마트 | 1,000m |
| `CONVENIENCE_STORE` | `CS2` | 편의점 | 1,000m |
| `HOSPITAL` | `HP8` | 병원 | 1,000m |
| `PHARMACY` | `PM9` | 약국 | 1,000m |
| `SCHOOL` | `SC4` | 학교 | 1,000m |

`application.yml`의 `zipick.infra.categories`로 관리한다. 화면 표시 순서도 이 순서를 따른다.
**값을 바꾸면 `policy_signature`가 달라져 다음 배치에서 전체가 자동 재수집된다.**

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

### 1. 추천 알고리즘 — `nearest` JSON

`housing_infra_snapshot.nearest`를 기존 `ScoreCalculator.scoreInfra()`가 기대하는 형식으로 저장해 두었다.

```json
{"SW8": {"name": "신설동역", "distanceM": 683}, "CS2": {"name": "세븐일레븐 보문파크점", "distanceM": 61}}
```

- **key는 서비스 코드가 아니라 카카오 카테고리 그룹 코드**(`SW8`)다.
- 0건 카테고리는 들어 있지 않다.
- 후보 주택 여러 건을 한 번에 읽으려면 `HousingInfraSnapshotRepository.findAllByHousingIdIn(ids)`를 쓴다.
  배치가 미리 채워 두므로 **외부 API 호출이 필요 없다.**

> 추천 연동은 아직 하지 않았다. 추천의 공급주택 구조 통합이 끝난 뒤 연결한다.
> 그때 **수집 대상을 `OPEN`으로 제한한 점**(모집 예정 공고는 수집하지 않음)을 함께 확인해야 한다.

### 2. 공고·공급주택 수집 — 실행 순서

인프라 수집은 공고 수집과 **코드로 연결돼 있지 않다.** `MyHomeSyncService`를 수정하지 않았다.
대신 수집 대상 조건에 `좌표가 있을 것`이 들어 있어, 좌표 보완이 끝난 주택만 자동으로 대상이 된다.

```
공고 수집 (NoticeCollectionApplication 또는 POST /api/internal/notices/sync)
  → 웹 서버 기동 → 다음 배치 주기에 인프라가 자동으로 붙는다
```

- `NoticeCollectionApplication`은 웹 서버 없이 뜨므로 **인프라 스케줄러가 실행되지 않는다.**
- 공고 수집 과정에서 좌표를 얻지 못한 주택은 인프라도 수집되지 않는다(`UNAVAILABLE`).
  **지오코딩 성공률이 곧 인프라 제공률이다.**

### 3. 프론트 — 상태 4값

`FRESH` / `STALE` / `PENDING` / `UNAVAILABLE`이 화면 분기의 계약이다.
다른 화면에서 인프라를 쓸 때도 `PENDING`(수집 대기)과 `UNAVAILABLE`(제공 불가)을 구분해 안내한다.

### 4. 카카오 로컬 API — 주소 검색과 공유하는 설정

주소 → 좌표 변환(`KakaoAddressClient`)과 **같은 REST 키, 같은 `kakaoLocalRestClient` 빈**을 쓴다.
`config/application-secret.yml`의 `kakao.local.rest-api-key` 하나로 둘 다 동작한다. 새 환경 변수는 없다.

단, **일일 쿼터는 API별로 따로 집계된다.** 주소 검색 사용량과 카테고리 검색 사용량은 별개다.

---

## 카카오 카테고리 장소 검색 필드

- 요청 주소: `GET https://dapi.kakao.com/v2/local/search/category.json`
- 헤더: `Authorization: KakaoAK {REST_API_KEY}`

### 요청 파라미터

| 필드 | ZIPICK 사용값 | 의미 |
| --- | --- | --- |
| `category_group_code` | `SW8` 등 | 카테고리 그룹 코드 |
| `x` / `y` | 주택 경도 / 위도 | 중심 좌표. **둘을 넘겨야 응답에 `distance`가 포함된다** |
| `radius` | 1000 / 1500 | 검색 반경(m). 허용 범위 0~20,000 |
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
DB를 새로 만들 때는 `local-init.sql` → `notice-init.sql` → **`infra-init.sql`** 순서다
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

로그에서는 수집이 끝날 때마다 아래 한 줄이 남는다. 대상이 0건이면 아무 로그도 남지 않는다.

```
주변 인프라 수집 완료: 대상=68, 저장=68, 데이터오류=0, 호출실패=0, 카카오 호출=408, 중단=false
```
