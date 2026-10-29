# 행정구역 표준화 (시도 · 시군구)

공급주택의 `province_name` / `district_name` 은 수집 경로마다 표기가 달랐다.
같은 지역이 `서울` / `서울시` / `서울특별시` 로 흩어지면 지도 군집과 추천의 지역 조건이 함께 깨진다.

이 문서는 **어디서 표준화하는지**, **배포할 때 무엇을 하면 되는지**,
**로컬 DB 를 재수집 없이 정리하는 방법**을 정리한 것이다.

---

## 1. 왜 흩어졌나

| 수집 경로 | 증상 |
| --- | --- |
| MyHome 공공 API | 대체로 정확하다(일반구 포함) |
| 첨부파일 파서 (LH PDF/XLS) | 공고문 주소를 잘라 쓴다 → 옛 명칭, 1토큰 절단, **토큰 밀림**(시도 자리에 시군구가 들어옴) |
| Kakao geocoding | `region_1depth_name` 이 **축약형**이다 (`서울`, `경기`) |

여기에 쓰기 경로가 셋이라(수집 / 좌표 보완 / AI 검수) 어느 한 곳에서만 고치면 다른 경로가 다시 덮어썼다.

---

## 2. 어디서 표준화하나 — 진입점은 한 곳

판정은 전부 `com.zipick.notice.support.RegionNormalizer` 가 한다. **순수 로직**이라 DB·Spring·외부 API 에 의존하지 않는다.

호출 지점은 **`Housing` 엔티티 하나**다. 지역 값을 건드리는 세 메서드가 모두 마지막에 `normalizeRegion()` 을 부른다.

```
Housing#synchronize(...)          수집 upsert          ┐
Housing#enrichAddress(...)        Kakao 좌표/주소 보완  ├→ normalizeRegion() → RegionNormalizer.normalize(...)
Housing#applyAiNormalization(...) AI 검수 패치          ┘
```

- `province_name` / `district_name` 에 값을 넣는 코드는 이 세 메서드뿐이다. 직접 SQL `UPDATE` 는 없다.
- 새 `Housing` 은 생성 직후 바로 `synchronize(...)` 를 거친 뒤 저장된다
  (`NoticeSyncPersistenceService`). **저장 전에 반드시 표준화를 통과한다.**
- 이미 들어 있던 값도 매번 다시 통과시킨다. `enrichAddress` 가 `fillWhenBlank` 라서
  기존 비표준 값을 Kakao 결과로 덮지 못하던 문제가 여기서 함께 풀린다.

### 원칙 — 근거가 있을 때만 바꾼다

확정할 수 없으면 원본을 유지하거나 비워 둔다. **추측하지 않는다.**

| 처리 | 예 |
| --- | --- |
| 축약형 → 정식 명칭 | `서울` → `서울특별시`, `경기` → `경기도` |
| 1:1 개칭 | `전라북도` → `전북특별자치도`, `강원도` → `강원특별자치도`, `제주도` → `제주특별자치도` |
| 행정구역 통합 | `광주광역시` · `전라남도` → `전남광주통합특별시` |
| 토큰 밀림 복구 | 시도 자리에 시군구가 들어온 경우, 주소에서 시도를 다시 찾는다 |
| 일반구 복구 | 주소에 `○○시 ○○구` 가 분명히 있을 때만 |
| **하지 않음** | `천안시` → `천안시 서북구` (주소에 근거가 없으면 추측하지 않는다) |
| **하지 않음** | `도청이전신도시` 같은 개발지구명을 시군구로 취급 (→ 비워 둔다) |

⚠️ **`광주` 축약형은 일부러 표에 넣지 않았다.** 광주광역시(현 전남광주통합특별시)와
경기도 광주시를 문맥 없이 구분할 수 없다.

⚠️ **통합 명칭(`MERGED_PROVINCES`)은 `AddressNormalizer` 에 넣지 않는다.**
그 결과가 `source_housing_key` 의 재료라서, 매핑을 바꾸면 다음 수집에서 같은 주택이 다른 키로 보여 중복 행이 생긴다.
가리키는 범위가 같은 1:1 개칭(`RENAMED_PROVINCES`)만 공유한다.

---

## 3. 배포 — 수동 backfill 이 필요 없다

배포 환경은 다음 절차를 쓴다.

```
DB 초기화 → 최신 코드로 전체 재수집 → 수집 경로가 RegionNormalizer 를 거침 → 처음부터 표준값으로 저장
```

2장에서 보듯 **저장 경로가 하나뿐이고 그 경로가 반드시 표준화를 거치므로**,
비우고 다시 수집하면 축약형·옛 명칭·토큰 밀림·일반구 누락이 모두 정리된 상태로 들어간다.
**backfill 은 배포 절차가 아니다.**

---

## 4. 로컬 개발 — 재수집 없이 정리하기

로컬에서는 전체 재수집이 오래 걸린다. 이미 쌓아 둔 DB 를 그대로 두고 지역 값만 맞추고 싶을 때
`scripts/dev/RegionBackfill.java` 를 쓴다.

### 성격

- **어떤 소스 세트에도 들어 있지 않다.** Gradle 이 컴파일하지 않고 jar 에도 들어가지 않는다.
  스케줄러·스타트업 훅·마이그레이션 도구가 이 파일을 실행할 경로가 없다.
- 판정은 운영 코드 `RegionNormalizer` 가 한다. 도구에 별도 규칙을 두지 않는다 —
  **정책이 둘로 갈라지면 DB 와 수집 결과가 어긋난다.**
- 기본이 dry-run 이다. `--apply` 를 직접 붙였을 때만 UPDATE 한다.
- 바꾸는 컬럼은 `housing.province_name` / `district_name` 둘뿐이다.

### 준비

```bash
cd zipick-backend && ./gradlew -q compileJava     # RegionNormalizer 컴파일 결과가 필요하다
```

classpath 는 컴파일된 클래스 + PostgreSQL 드라이버 두 개다.

```bash
# Git Bash
PGJAR=$(ls ~/.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql/*/*/postgresql-*.jar | grep -v sources | head -1)
CP="zipick-backend/build/classes/java/main;$PGJAR"
```

> Windows 의 java 는 `;` 로 구분한다. Git Bash 에서 넘길 때는 `cygpath -w` 로 경로를 바꿔 준다.
> macOS / Linux 는 구분자가 `:` 다.

### ① dry-run — 무엇이 바뀌는지만 본다

```bash
java -cp "$CP" scripts/dev/RegionBackfill.java --dry-run \
  --user=postgres --password=***
```

출력 형식은 다음과 같다(건수는 DB 상태에 따라 다르다).

```
읽은 housing: <N>건 (jdbc:postgresql://localhost:5432/zipick)
변경 대상: <M>건

[유형별]
  시도 표준화            <n>건
  시군구 보정            <n>건
  좌표 근거 시도 복구      <n>건
  시도+시군구 동시 보정    <n>건
  판정 불가 → 비움         <n>건

[before → after]
  #1203  서울           / 종로구            →  서울특별시       / 종로구            [시도 표준화]
  …

[dry-run] 아무것도 바꾸지 않았습니다. 적용하려면 --apply --backup=<경로> 를 붙이세요.
```

> 참고: 2026-10-27 이 저장소의 로컬 DB(housing 449건)에서는 103건이 대상이었고,
> 그중 6건이 좌표 근거 시도 복구, 2건이 `도청이전신도시`처럼 판정 불가라 비워 둔 행이었다.
> 그 DB 는 이미 적용을 마쳤으므로 지금 dry-run 하면 0건이어야 한다.

계정은 `--user` / `--password` 또는 `ZIPICK_DB_USER` / `ZIPICK_DB_PASSWORD` 환경 변수로 넘긴다.
접속 주소는 `--url=` 로 바꿀 수 있다(기본 `jdbc:postgresql://localhost:5432/zipick`).

### ② apply — 명시적으로 실행했을 때만

```bash
java -cp "$CP" scripts/dev/RegionBackfill.java --apply \
  --backup=~/zipick-region-backup.csv --user=postgres --password=***
```

- `--backup` 은 **필수**다. 없으면 실행을 거부한다. **저장소 밖 경로**를 쓴다.
- 백업 CSV 에 `housing_id, old_province_name, old_district_name, new_province_name, new_district_name` 를 남긴다.
- 한 트랜잭션에서 처리하고, 계획한 건수와 실제 영향 행 수가 다르면 **ROLLBACK** 한다.

### ③ 다시 dry-run — 0건이어야 한다

```
변경 대상: 0건
```

같은 입력에 같은 결과를 내므로(idempotent) 두 번째부터는 바꿀 것이 없다.

---

## 5. 도구가 할 수 있는 것 / 하면 안 되는 것

### 1패스 — 운영 코드 그대로

`RegionNormalizer.normalize(province, district, address, normalized_address)` 를 각 행에 그대로 돌린다.
**재수집했을 때 저장될 값과 같다.** 3장 표의 처리가 전부 여기서 일어난다.

### 2패스 — 이 도구에만 있는 보정

1패스가 **시군구는 확정했는데 시도를 확정하지 못한 행**만 대상이다.
**같은 좌표**에 있으면서 1패스가 시도까지 확정한 다른 주택을 근거로 채운다.

- 후보 시도가 **정확히 하나일 때만** 채운다. 둘 이상이면 건드리지 않는다.
- 좌표가 없으면 하지 않는다.
- 재수집하면 원본 필드에서 같은 값이 나오므로 결과가 어긋나지 않는다.

토큰이 밀려 들어와 시도를 잃은 행(실측 6건)이 이 경로로 복구된다.

### 절대 하지 않는 것

- 주소에 근거가 없는 시군구 추측 (`천안시` → `천안시 서북구` ❌)
- 좌표 근거가 여럿일 때의 임의 선택
- `housing` 의 다른 컬럼, `notice` / `housing_supply` / 사용자 희망조건 변경
- 자동 실행 — 스타트업 훅, 스케줄러, Flyway/Liquibase, 배포 파이프라인 어디에도 걸지 않는다

---

## 6. 지역 값을 읽는 쪽

- **지도 프론트엔드** — 백엔드가 준 `province` / `district` 를 그대로 쓴다.
  문자열을 임의로 보정하지 않는다. [`docs/map-clustering.md`](./map-clustering.md) 7장.
- **추천 지역 조건** — `RegionMatcher` 가 `"시도 시군구"` 키로 비교하며,
  **상위 지역이 일반구 하위를 포함**한다(`경기도 부천시` 희망 → `부천시 소사구` 주택이 걸린다).
- **필터 조회** — `HouseFilterPredicates.regionIn()` 이 같은 계층 규칙을 SQL 로 적용한다.

세 곳이 같은 표기를 전제하므로, **표기를 바꾸려면 `RegionNormalizer` 한 곳만 고치고 전체를 다시 수집한다.**
