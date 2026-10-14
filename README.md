# ZIPICK (집픽)

공공임대 공급주택을 지도에서 탐색하고, 사용자 자격정보와 희망 주거조건을 바탕으로 추천 점수와 근거를 제공하는 서비스.

```
zipick-backend/    Java 25 / Spring Boot 4.1.1 / Spring Security / JPA / MySQL
zipick-frontend/   React / TypeScript / Vite
```

## 백엔드 로컬 실행

### 1. 사전 준비

- JDK 25 (Gradle toolchain 이 사용한다)
- 로컬 MySQL

Gradle 은 wrapper(9.3)를 사용하므로 별도 설치가 필요 없다.

### 2. 스키마 생성

```sql
CREATE DATABASE IF NOT EXISTS zipick
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. 환경 변수 설정

아래 표를 참고해 환경 변수를 설정한다. **실제 값은 Git 에 커밋하지 않는다.**

| 이름 | 용도 | 필수     | 기본값         |
| --- | --- |--------|-------------|
| `DB_HOST` | MySQL 호스트 | 선택     | `localhost` |
| `DB_PORT` | MySQL 포트 | 선택     | `3306`      |
| `DB_NAME` | 스키마 이름 | 선택     | `zipick`    |
| `DB_USERNAME` | MySQL 계정 | 선택     | `root`       |
| `DB_PASSWORD` | MySQL 비밀번호 | **필수** | 없음          |
| `JWT_SECRET` | JWT(HS256) 서명 키 | **필수** | 없음          |

`JWT_SECRET` 은 **Base64 로 인코딩된 문자열**이어야 하며, 디코딩한 결과가 **32바이트(256비트) 이상**이어야 한다.
Base64 가 아니거나 길이가 모자라면 애플리케이션 기동 시 오류가 발생한다.
아래 명령으로 생성한 값을 그대로 사용한다.

```bash
openssl rand -base64 32 | tr -d '\n'
```

생성한 값은 각자 로컬 환경 변수로만 설정하고 Git 에 커밋하지 않는다.

설정 예시 — 아래 `{...}` 자리에 본인 값을 넣는다.

**IntelliJ** — Run/Debug Configurations > Environment variables

```text
DB_USERNAME={your_mysql_user};DB_PASSWORD={your_mysql_password};JWT_SECRET={your_jwt_secret}
```

**Windows PowerShell**

```powershell
$env:DB_USERNAME = "{your_mysql_user}"
$env:DB_PASSWORD = "{your_mysql_password}"
$env:JWT_SECRET = "{your_jwt_secret}"
```

**macOS / Linux**

```bash
export DB_USERNAME="{your_mysql_user}"
export DB_PASSWORD="{your_mysql_password}"
export JWT_SECRET="{your_jwt_secret}"
```

### 4. 스키마 초기화

로컬 프로파일은 `ddl-auto: validate` 라 테이블을 자동 생성하지 않는다.
초기화 DDL 을 직접 실행한다.

```bash
mysql -u {your_mysql_user} -p zipick < zipick-backend/db/local-init.sql
```

> `zipick-backend/db/local-init.sql` 은 기존 테이블을 DROP 후 재생성하므로 **데이터가 모두 삭제된다. 로컬 전용이며 운영 DB 에는 적용하지 않는다.**
> 엔티티가 추가·변경되면 초기화 DDL 도 함께 갱신해 커밋한다.

### 5. 실행

```bash
cd zipick-backend
./gradlew build          # 빌드
./gradlew bootRun        # 실행
```

### 6. 예시 데이터 (선택)

추천·필터를 바로 확인하려면 스키마 초기화 뒤 예시 데이터를 넣는다. 공고·주소는 모두 가짜다.

```bash
mysql -u {your_mysql_user} -p zipick < zipick-backend/db/local-seed.sql
```

- Swagger UI: http://localhost:8080/swagger-ui.html

## 프론트엔드 로컬 실행

```bash
cd zipick-frontend
npm install
npm run dev     # http://localhost:5173 (/api 요청은 8080으로 프록시)
```

## 추천 · 기본 필터링 (안서호)

### 임시 인증 (추천 기능 개발용)

로그인(카카오·JWT)이 붙기 전까지 `SecurityConfig`가 모든 API를 열어 두고, 백엔드는 `X-User-Id` 헤더로 사용자를 식별한다.
프론트 헤더 오른쪽의 "사용자" 드롭다운으로 시드 사용자 1·2를 바꿔 볼 수 있다.
JWT가 완성되면 `SecurityConfig`, `CurrentUserArgumentResolver`(백엔드)와 `api/client.ts`의 인터셉터(프론트)를 교체하면 된다.

### 추천 알고리즘

사전 기입 정보(기본 정보 + 희망 주거조건)로 공급주택마다 0~100점 추천도를 계산한다.

```
1. 공고 지원조건 판정   → 연령·무주택·소득·자산·자동차 기준(공공임대 유형별). 미충족 제외, 판단 불가는 '확인 필요'
2. 항목별 점수 (0~1)    → 비용·지역·면적·인프라·주택 유형
3. 가중합               → 기본 가중치 × 사용자 우선순위(1~5), 계산 가능한 항목만으로 정규화
                          × 지원조건 배수 (충족 1.0, 확인 필요 0.7)
4. 순위 정리            → 60점 이상, 점수순(동점은 마감 임박순), 공고당 최대 5곳, 최대 30곳
```

| 항목 | 기본 가중치 | 점수 기준 |
| --- | --- | --- |
| 비용 | 35 | 월 환산 비용(월세 + 보증금 × 전환이율 ÷ 12)이 예산 이하 1점, 30% 초과 0점 |
| 지역 | 25 | 관심 지역 1순위 1.0, 2순위 0.8, 3순위 0.6, 그 외 0 |
| 면적 | 15 | 희망 범위 안 1점, 10㎡ 벗어날 때마다 0.25 감점 |
| 인프라 | 15 | 시설별 500m 이내 1점, 1,500m 0점, 사용자 인프라 가중치로 가중 평균 |
| 유형 | 10 | 희망 주택 유형(아파트·다세대…) 일치와 희망 공공임대 유형(매입임대·행복주택·국민임대…) 일치의 평균 |

- 값이 없는 항목은 계산에서 빼고 남은 가중치를 다시 나눈다. 확인 가능한 항목의 가중치 합이 60% 미만이면 "정보 부족"으로 제외한다.
- 추천 기준(가중치·꼭/되면 좋음 규칙·인프라 가중치)은 사용자당 최대 3개 저장하고 전환할 수 있다. `MUST` 항목이 0점이면 제외한다.
- 모든 수치는 `application.yml`의 `zipick.recommendation`에서 조정한다. 지원조건 기준값은 참고용이며 최종 자격은 공고문 기준이다.
- 엔진 단위 테스트(43개): `zipick-backend/src/test/java/com/zipick/recommendation/engine`

### 우선순위 찾기 (AHP · 쌍대 선택 학습)

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

### API

| Method | URI | 설명 |
| --- | --- | --- |
| GET, PUT | /api/v1/profiles/me | 기본 정보 |
| GET, PUT | /api/v1/profiles/me/preferences | 희망 주거조건 |
| GET | /api/v1/houses | 전체 공급주택 (점수 없이 필터만). 추천 목록과 같은 필터 쿼리 + `limit`(기본 500). 로그인 불필요 |
| GET | /api/v1/houses/filter-options | 필터 값별 모집 중인 집 수. 0건인 기관·유형 칩은 꺼진다 |
| GET | /api/v1/recommendations | 추천 목록. 필터 쿼리: regions, depositMin/Max, rentMin/Max, areaMin/Max, housingTypes, statuses, agencies, rentalTypes, includeUnknown. `preview=true`면 후보 전체(최대 200)를 점수와 함께 반환 |
| GET | /api/v1/recommendations/{houseId} | 집 하나의 점수와 근거 |
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

프론트는 `preview=true`로 받은 항목별 점수로 가중치 슬라이더를 움직일 때 서버 없이 즉시 다시 순위를 매긴다(`src/utils/score.ts`, 서버와 같은 공식).

### 기본 필터링

- 추천 공급주택과 전체 공급주택이 **같은 필터**(`HouseFilterParams` → `HouseFilter` → QueryDSL)를 쓴다. 화면 위 전환 버튼으로 오가도 필터(URL 쿼리)가 유지된다.
- 필터는 저장된 추천조건을 바꾸지 않고 지금 보이는 결과만 좁힌다.
- 필터 항목: 지역, 보증금·월세·면적 범위, 주택 유형, 모집 상태(전체/공고중/접수중), 공급기관(LH·SH·GH·HUG), 공공임대 유형(매입임대·행복주택·국민임대·든든전세), 정보 없는 집 포함 여부.
- 공급기관·유형 칩은 `/filter-options`의 개수로 켜고 끈다. 수집 기관이 늘면 코드 수정 없이 켜진다.

### 다른 담당자와의 접점

- `housing` 패키지의 `Announcement`·`SupplyHouse`·`InfraSummary`는 추천에 필요한 최소 컬럼만 정의했다. 공고 수집·인프라 수집 담당이 확장하면 된다. 추천은 `SupplyHouse.sigunguCode`, `deposit`, `monthlyRent`, `exclusiveArea`, `housingType`과 `InfraSummary.nearest`(카카오 카테고리별 최단 거리)를 쓴다.
- 지도 담당은 "추천" 모드면 `/api/v1/recommendations`의 `lat`·`lng`·`score`, "전체" 모드면 `/api/v1/houses`의 `lat`·`lng`로 핀을 그리면 된다. 두 API는 같은 필터 쿼리를 받는다.
- 주변 인프라 담당은 `infra_summaries.nearest`에 `{"SW8":{"name":"망원역","distanceM":420}, ...}`(카카오 카테고리 코드별 최단 거리) 형식으로 넣어 주면 인프라 점수에 바로 반영된다.
- 국민임대 지원조건(소득·자산 상한)은 참고용 근사치다. 공고문 기준으로 `application.yml`의 `eligibility.NATIONAL`을 확정해야 한다.
- 기본 정보의 학생 여부·청약저축은 지금은 저장·표시만 하고 판정에는 쓰지 않는다.
