# 지도 핀 군집화 — 현재 구현과 행정구역별 클러스터링 개선 계획

ZIPICK 지도는 전체 공고와 맞춤 추천을 같은 `MapPanel` 위에서 전환해 보여 준다.
공급주택이 수백~수천 곳이라 핀을 그대로 뿌리면 서로 겹쳐 읽을 수 없어 군집화를 쓴다.

이 문서는 **지금 들어가 있는 일반 클러스터링**과, 앞으로 가려는 **행정구역별 클러스터링**의
차이·필요한 데이터·수정 지점을 정리한 것이다.

---

## 1. 현재 구현 (일반 클러스터링)

### 구조

```
features/map/
  MapPanel.tsx     지도 인스턴스 생성·유지, 선택 상태, 영역 변경 알림
  clustering.ts    ← 군집화는 여기에만 있다 (교체 지점)
  kakaoMaps.ts     SDK 로더 + 타입. libraries=clusterer 를 함께 받는다
  types.ts         MapPin 계약
```

`MapPanel` 은 지도를 한 번만 만들고, 핀 목록이 바뀌면 `clustering.ts` 가 돌려준
`PinLayer` 에 넘기기만 한다. 군집 방식을 바꿀 때 **`clustering.ts` 하나만 갈아 끼우면 된다.**

```ts
export interface PinLayer {
  render(pins: MapPin[]): void;        // 핀 목록 통째 교체
  highlight(housingId: number | null): void;  // 선택 강조 (지도는 안 움직인다)
  destroy(): void;                      // 마커·리스너 정리
}
```

### 동작

| 항목 | 현재 |
| --- | --- |
| 군집 엔진 | 카카오 지도 SDK `maps.MarkerClusterer` (`libraries=clusterer`) |
| 군집 기준 | **화면 좌표가 가까운 마커**를 확대 수준에 따라 묶는다 |
| 군집 숫자 | 마커 수 = **고유 Housing 수**. 서버가 주택 단위로 내려 주므로 호실이 여러 개여도 핀은 하나다 |
| 군집 해제 | `minLevel: 6` — 그보다 확대하면 개별 핀이 보인다 |
| 군집 클릭 | `disableClickZoom: true` 로 두고 직접 처리 — 중심 이동 + 2단계 확대 |
| 개별 핀 | `maps.Marker` + SVG `MarkerImage` (선택 시 빨강·확대) |
| 핀 클릭 | `onSelect(housingId)` → URL `?housing=` 갱신 → 상세 패널 |
| 지도 이동 | **첫 결과에서만** 전체가 보이게 맞춘다. 이후 필터 변경에는 중심·확대를 유지한다 |
| 정리 | `destroy()` 가 마커를 떼고 등록한 리스너를 모두 해제한다 |

> `CustomOverlay` 기반이던 기존 핀은 `MarkerClusterer` 가 `Marker` 만 받기 때문에
> `Marker` 로 바꿨다. 선택·상세 패널·URL 상태 등 사용자 흐름은 그대로다.

### 한계

- 군집에 **숫자만** 뜬다. "서울에 몇 곳"인지 알 수 없다.
- 묶는 기준이 화면 좌표라, 같은 구인데 멀리 떨어지면 다른 군집이 된다.
- 전국에서 축소하면 군집이 행정 경계와 무관하게 생겨 지역 감각을 주지 못한다.

---

## 2. 가려는 모습 (행정구역별 클러스터링)

| 확대 수준 | 군집 단위 | 표시 |
| --- | --- | --- |
| 전국 (레벨 ~11 이상) | 시도 | `서울 124곳` `경기 86곳` |
| 중간 (레벨 ~7–10) | 시군구 | `강남구 12곳` `성동구 8곳` |
| 상세 (레벨 ~6 이하) | 개별 주택 | 지금과 같은 핀 |

- 군집에 **지역명 + 고유 Housing 수**를 함께 쓴다.
- 군집을 누르면 그 지역 범위로 확대한다(한 단계씩이 아니라 지역 경계에 맞춰).

### 일반 클러스터링과의 차이

| | 일반 (현재) | 행정구역별 (목표) |
| --- | --- | --- |
| 묶는 기준 | 화면 좌표 근접 | `province` / `district` 값 |
| 군집 위치 | 묶인 마커의 평균 좌표 | **지역 대표 좌표** 가 필요하다 |
| 군집 라벨 | 숫자 | 지역명 + 수 |
| 확대 단계 | SDK 가 알아서 | 레벨 구간 → 행정 단위 매핑을 우리가 정한다 |
| 계산 위치 | SDK | 프론트 집계 또는 서버 집계 |

---

## 3. 필요한 데이터

1. **주택별 행정구역** — 핀 응답에 `province` / `district` 가 있어야 묶을 수 있다.
   `MapHousingSummaryResponse` 는 현재 9필드이고 `address` 문자열만 있다.
   주소 파싱은 부정확하므로 **백엔드 DTO 에 두 필드를 추가하는 편이 낫다**
   (`Housing.provinceName` / `districtName` 에 이미 있는 값이라 쿼리 변경은 필요 없다).
   → 지도 담당자 승인 후 진행할 것.
2. **지역 대표 좌표** — 시도 17개 + 시군구 약 250개의 중심 좌표.
   - 방법 A: 정적 상수 파일(`features/map/regionCenters.ts`). 외부 의존 없음, 데이터가 고정됨.
   - 방법 B: 해당 지역 주택들의 평균 좌표. 추가 데이터가 필요 없지만 군집 위치가 결과에 따라 흔들린다.
   - 권장: **A**. 축소 상태에서 위치가 안정적이어야 지역 감각이 생긴다.

---

## 4. 구현 방법

### 집계 위치

- **프론트 집계(권장, 작은 변경)** — 핀 응답을 받아 `province`/`district` 로 묶는다.
  현재 규모(약 1,400주택)에서는 비용이 무시할 만하다.
- 서버 집계(확장 대비) — `/api/v1/map/housings/clusters?level=` 같은 엔드포인트.
  주택 수가 수만 건이 되면 그때 검토한다. 지금 하면 과한 구조다.

### 레벨 → 단위 매핑

```ts
function clusterUnit(level: number): 'province' | 'district' | 'none' {
  if (level >= 11) return 'province';
  if (level >= 7) return 'district';
  return 'none';   // 개별 핀
}
```

`MapPanel` 은 이미 `idle` 이벤트를 듣고 있으므로, 여기에 레벨 변화를 받아
`PinLayer.render()` 를 다시 부르면 된다.

### 군집 마커

지역명을 넣으려면 숫자만 그리는 기본 클러스터러로는 부족하다.
`CustomOverlay` 로 직접 그리는 편이 자유롭다.

```
[ 강남구 ]
[   12   ]
```

즉 **행정구역 군집은 MarkerClusterer 를 쓰지 않고 직접 그리는 쪽**이 된다.
`clustering.ts` 가 `PinLayer` 계약만 지키면 `MapPanel` 은 바꾸지 않아도 된다.

---

## 5. 수정해야 할 파일

| 파일 | 변경 |
| --- | --- |
| `features/map/clustering.ts` | 핵심. `createPinLayer` 를 행정구역 집계 방식으로 교체 |
| `features/map/regionCenters.ts` (신규) | 시도·시군구 대표 좌표 |
| `features/map/MapPanel.tsx` | 확대 수준 변화를 `PinLayer` 에 전달 (`idle` 리스너 재사용) |
| `features/map/types.ts` | `MapPin` 에 `province` / `district` 추가 |
| `api/mapHousing.ts` | 응답 타입에 두 필드 추가 |
| `pages/MapPage.tsx` | `toMapPins` / `recommendationPins` 에서 지역 값 채우기 |
| 백엔드 `MapHousingSummaryResponse` | `provinceName` / `districtName` 추가 (**승인 필요**) |

추천 모드도 같은 `PinLayer` 를 쓰므로, 추천 응답(`RecommendedHouse`)에는 이미
`province` / `district` 가 있어 추가 작업이 없다.

---

## 6. 성능·정합성 고려사항

- **군집 수 = 고유 Housing 수**를 유지할 것. 공급 호실 수로 세면 숫자가 부풀어 오른다.
  서버가 주택 단위로 내려 주므로 핀 배열 길이가 곧 주택 수다.
- `district` 가 비어 있는 주택이 있다(주소 정규화 실패). **"지역 미상" 군집**을 따로 만들거나
  개별 핀으로 남겨야 조용히 사라지지 않는다.
- 좌표가 없는 주택은 애초에 핀이 없다. 전체 공고 모드는 서버가 빼고, 추천 모드는
  목록에만 남는다 — 군집 숫자와 목록 수가 다를 수 있으니 안내 문구를 함께 유지할 것.
- 필터를 바꿀 때 지도 중심·확대 수준을 건드리지 말 것. 현재 구현의
  `fittedRef` 가 그 역할을 한다(첫 결과에서만 맞춘다).
- 마커·오버레이를 교체할 때 **이전 것을 반드시 `setMap(null)` 하고 리스너를 해제**할 것.
  `PinLayer.destroy()` 가 그 책임을 진다.
