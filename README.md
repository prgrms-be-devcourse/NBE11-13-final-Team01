# ZIPICK

내 조건 맞춤 공공임대를 찾고 지원까지 챙기는 지도.

## 구성

| 폴더 | 내용 | 기술 |
| --- | --- | --- |
| `zipick-backend` | Spring Boot API | Java 25, Spring Boot 3.5, JPA, QueryDSL, H2(로컬)/MySQL(운영) |
| `zipick-frontend` | 웹 화면 | React 18, TypeScript, Vite, TanStack Query |

## 로컬 실행

```bash
# 백엔드 (H2 인메모리 + 시드 데이터, Docker 불필요)
cd zipick-backend && ./gradlew bootRun
# Swagger: http://localhost:8080/swagger-ui.html
# H2 콘솔: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:mem:zipick, 사용자 sa)

# 프론트엔드
cd zipick-frontend && npm install && npm run dev
# http://localhost:5173  (/api 요청은 8080으로 프록시)
```

운영 프로필(`--spring.profiles.active=prod`)은 MySQL을 쓴다. `docker compose up -d`로 MySQL을 띄울 수 있다.

### 임시 인증

로그인(카카오·JWT)이 붙기 전까지 백엔드는 `X-User-Id` 헤더로 사용자를 식별한다.
프론트 헤더 오른쪽의 "사용자" 드롭다운으로 시드 사용자 1·2를 바꿔 볼 수 있다.
JWT가 완성되면 `CurrentUserArgumentResolver`(백엔드)와 `api/client.ts`의 인터셉터(프론트)만 교체하면 된다.

## 추천 알고리즘 (안서호)

사전 기입 정보(기본 정보 + 희망 주거조건)로 공급주택마다 0~100점 추천도를 계산한다.

```
1. 공고 지원조건 판정   → 연령·무주택·소득·자산·자동차 기준. 미충족 제외, 판단 불가는 '확인 필요'
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
| 유형 | 10 | 희망 주택 유형이면 1점 |

- 값이 없는 항목은 계산에서 빼고 남은 가중치를 다시 나눈다. 확인 가능한 항목의 가중치 합이 60% 미만이면 "정보 부족"으로 제외한다.
- 추천 기준(가중치·꼭/되면 좋음 규칙·인프라 가중치)은 사용자당 최대 3개 저장하고 전환할 수 있다. `MUST` 항목이 0점이면 제외한다.
- 모든 수치는 `application.yml`의 `zipick.recommendation`에서 조정한다. 지원조건 기준값은 참고용이며 최종 자격은 공고문 기준이다.
- 엔진 단위 테스트: `zipick-backend/src/test/java/com/zipick/recommendation/engine`

### API

| Method | URI | 설명 |
| --- | --- | --- |
| GET, PUT | /api/v1/profiles/me | 기본 정보 |
| GET, PUT | /api/v1/profiles/me/preferences | 희망 주거조건 |
| GET | /api/v1/recommendations | 추천 목록. 필터 쿼리: regions, depositMin/Max, rentMin/Max, areaMin/Max, housingTypes, statuses, agencies, rentalTypes, includeUnknown. `preview=true`면 후보 전체(최대 200)를 점수와 함께 반환 |
| GET | /api/v1/recommendations/{houseId} | 집 하나의 점수와 근거 |
| GET | /api/v1/recommendations/relax-suggestions | 추천이 5곳 미만일 때 조건 완화 제안 |
| GET, POST | /api/v1/recommendation-profiles | 추천 기준 목록·생성 |
| PATCH, DELETE | /api/v1/recommendation-profiles/{id} | 추천 기준 수정·삭제 |
| POST | /api/v1/recommendation-profiles/{id}/activate | 사용할 기준 전환 |
| POST, DELETE | /api/v1/recommendation-feedbacks[/{id}] | 숨김·별로예요 (같은 이유 2회면 조건 변경 제안) |

프론트는 `preview=true`로 받은 항목별 점수로 가중치 슬라이더를 움직일 때 서버 없이 즉시 다시 순위를 매긴다(`src/utils/score.ts`, 서버와 같은 공식).

### 다른 담당자와의 접점

- `housing` 패키지의 `Announcement`·`SupplyHouse`·`InfraSummary`는 추천에 필요한 최소 컬럼만 정의했다. 공고 수집·인프라 수집 담당이 확장하면 된다. 추천은 `SupplyHouse.sigunguCode`, `deposit`, `monthlyRent`, `exclusiveArea`, `housingType`과 `InfraSummary.nearest`(카카오 카테고리별 최단 거리)를 쓴다.
- 지도 담당은 추천 목록 응답의 `lat`·`lng`·`score`로 핀을 그리면 된다.
