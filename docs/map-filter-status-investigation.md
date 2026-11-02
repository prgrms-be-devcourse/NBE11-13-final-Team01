# 전체 공고 필터와 공고중 조회 조사

현재 전체 공고 필터에서는 사용자 결정에 따라 **모집 상태 선택을 제거했다.** 지도 요청은 `statuses`를 보내지 않고 서버의 기존 공개 기본 범위(UPCOMING+OPEN)를 사용한다. 옛 `/map?statuses=...` 링크도 전체 공고에서 해당 키만 정리해 보이지 않는 제한이 남지 않게 했다. 다른 필터·주택 선택·추천과 백엔드 상태 정책은 유지한다.

이하 내용은 제거 전 조사 기록이다. 당시 `공고중` 선택 시 0곳이 되는 현상은 실제 저장 데이터와 일치했다. 상태를 강제 치환하거나 백엔드·DB·수집 정책을 수정하지 않았다.

## 프론트에서 DB까지의 경로

- `FilterPanel.tsx`의 전체 = `UPCOMING,OPEN`, 공고중 = `UPCOMING`, 접수중 = `OPEN`이다. 기본값도 두 상태다.
- `filterParams.ts`와 `filterToParams`는 같은 값을 URL/API의 `statuses`에 전달한다. 지도용 `/api/v1/map/housings`는 이 필터를 받는다.
- `MapHousingController`는 `MapHousingFilterPolicy`로 요청 상태와 공개 상태 `{UPCOMING,OPEN}`의 교집합을 적용한다. UPCOMING은 제거되지 않는다.
- `HousingPinQueryRepository`는 `notice.status`로 조회하고 활성 주택·위도/경도 보유 조건을 적용한다. 선택한 공급 조건이 있을 때만 같은 활성 호실에 EXISTS를 건다.
- `MapHousingQueryService`는 조회 결과의 크기를 total로 돌려준다. 프론트 `toMapPins`·클러스터 레이어에는 UPCOMING을 제외하는 별도 상태 필터가 없다.

## 실제 개발 API와 로컬 PostgreSQL

개발 API에 직접 GET 요청하고 PostgreSQL의 `BEGIN TRANSACTION READ ONLY`에서 집계 후 ROLLBACK했다. 계정·주택 상세·비밀 설정은 기록하지 않았다. 수집이 진행되면 수치는 바뀔 수 있다.

| 상태 | DB 공고 | 연결 주택 | 활성·좌표 보유 주택 | 지도 API total |
| --- | ---: | ---: | ---: | ---: |
| UPCOMING | 0 | 0 | 0 | 0 |
| OPEN | 86 | 352 | 343 | 343 |
| CLOSED | 1 | 2 | 2 | 공개 지도에서 제외 |
| CANCELLED | 0 | 0 | 0 | 공개 지도에서 제외 |

전체 housing은 354행이며 모두 active였다. 상태를 지정하지 않은 지도 API 및 `statuses=UPCOMING,OPEN`도 343곳이다. 필터 옵션 API의 상태 집계는 OPEN 352, CLOSED 2이며 UPCOMING은 키가 없다. 좌표가 없는 9곳은 지도 목록에서 제외되므로 옵션 집계와 지도 total이 다른 것은 기존 정책이다.

## 용어와 상태 계산 정책

DB의 원천 `raw_status=공고중` 공고 19건은 모두 **OPEN**이다. 나머지 OPEN은 원천 일반공고 57건·정정공고 10건이다. 화면의 `공고중` 라벨은 UPCOMING이므로 원천 문구와 같은 의미로 사용되고 있지 않다.

`NoticeStatusEvaluator`의 현재 정책은 취소/마감 문구와 지난 종료일을 먼저 처리하고, **종료일이 없으면서 원천 상태에 예정/공고전이 있는 경우** UPCOMING으로 분류한다. 그 외는 OPEN이다. 시작일이 미래라는 이유만으로 UPCOMING으로 바꾸지 않는다. 기존 `NoticeStatusEvaluatorTest.keepsFutureApplicationOpenWhenItsEndDateHasNotPassed`도 이 동작을 명시한다. 상태는 수집의 `NoticeSyncPersistenceService`에서 평가해 저장하며 지도 조회 시 날짜로 다시 계산하지 않는다.

추가 관찰로, DB의 OPEN 중 시작일이 미래인 공고는 34건, 종료일이 조회 시점보다 지난 공고는 14건이었다. AI 날짜 보정의 `Notice.applyAiNormalizedDates`는 날짜만 수정하고 상태를 재평가하지 않는다. 이 수치의 원인을 확정하려면 수집/보정 시점과 상태 갱신 정책을 담당자와 추가 확인해야 한다. 이번 UPCOMING 0건을 해결하기 위해 이를 변경하지 않았다.

**별도 결정이 필요한 사항:** 화면의 공고중 라벨을 원천 공고중과 같은 의미로 만들지, 공고 예정 의미를 유지할지, 날짜 보정 이후 상태를 언제 갱신할지. 모두 비즈니스 의미·수집/정규화 정책에 영향을 주므로 이번 필터 UI 작업에서 임의 변경하지 않는다.

## 접수 예정 표시명 변경 검토

드로어 애니메이션 보완 시 상태 의미를 다시 확인했다. UPCOMING 판정은 실제 접수 시작일이 미래인지 확인하지 않으며, 원천의 예정/공고전 문구와 종료일 부재가 기준이다. 따라서 **접수 예정이라는 날짜 기반 의미를 보장하지 않는다.** OPEN도 접수 시작 전 공고를 포함하므로 현재 접수 가능하다는 의미를 보장하지 않는다.

같은 로컬 DB를 READ ONLY로 재확인한 결과 UPCOMING은 0건이며 OPEN 86건 중 시작일 미래 34건, 종료일 경과 14건, 시작일 없음 9건, 종료일 없음 10건이었다. 각 집계는 서로 겹칠 수 있다. 요청한 새 명칭의 의미가 확인되지 않았으므로 이번에는 **공고중 → 접수 예정으로 변경하지 않고**, 전체/공고중/접수중 표시와 API 값·필터 로직을 유지했다. UPCOMING 0건도 기존 빈 결과로 처리한다.

종료일이 지난 OPEN 14건은 애니메이션 작업과 별도 상태 갱신 문제로 남긴다. 수집 시 상태 판정과 AI 날짜 보정 이후의 재평가 시점 확인이 필요하며 이번 작업에서 DB 보정·상태 계산을 수행하지 않았다.

## 지역·옵션 건수의 범위

`HouseFilterService.options()`는 기존 `CandidateQueryRepository.countBy`를 사용한다. 상태 건수는 모든 상태의 활성 주택/활성 공급 호실을, 기관·임대 유형·주택 유형·지역은 UPCOMING+OPEN의 활성 주택/활성 공급 호실을 대상으로 `housing.id.countDistinct()` 등 기존 분류 집계 규칙을 사용한다. 현재 지도 필터나 좌표 보유 여부는 집계 조건에 없다.

드로어는 같은 `['filter-options']` 쿼리와 `/api/v1/houses/filter-options` 응답의 key/count를 직접 표시한다. 시·도 합계는 현재 응답에 없으므로 하위 건수를 더하지 않는다. 응답에 상위 지역 키와 건수가 제공되면 그대로 표시한다. 집계에 없는 저장/관심 지역은 숫자 없이 복원·해제할 수 있다. 기존 유형 옵션의 누락 키 = 0건 비활성 정책은 유지한다.

## 적용과 회귀 검증

- draft 변경과 초기화는 조회하지 않는다. 적용할 때 기존 query key로 결과를 먼저 확인한 뒤 성공 시 기존 URL 변환 함수를 호출한다. 새 결과 캐시의 1초 신선도를 이용해 직후 중복 조회를 피한다. 실패하면 URL·기존 핀·draft를 유지한다.
- `zipick-frontend/scripts/verify-map-filter.cjs`는 새 UI의 상태/지역/범위 입력·취소·오류·중복 요청·추천 분리를 검증한다. UPCOMING 데이터가 **있는 대체 응답**으로 해당 핀이 표시됨도 검증하므로 0건에 맞춘 우회가 없다.
- `--live-api`는 실제 공개 개발 API로 전체/UPCOMING/OPEN 및 지역 조합을 검증한다. 인증과 지도 SDK는 대체 구현이다. `--live-api --probe-sdk`는 실제 Kakao SDK 로드를 별도로 시도하며 결과를 구분해 저장한다.
- 브라우저 검증은 Vite와 별도 프로필의 headless Chrome CDP가 실행된 상태에서 `node scripts/verify-map-filter.cjs`로 수행한다. 기본 CDP 포트는 9341이며 `ZIPICK_CDP_URL`, `ZIPICK_TEST_BASE_URL`로 바꿀 수 있다. 캡처와 JSON은 OS 임시 폴더의 `zipick-map-filter-drawer-review`에 저장한다.
