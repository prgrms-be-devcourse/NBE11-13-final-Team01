import type { MapPin } from './types';

/**
 * 행정구역별 군집 집계. **카카오 지도 SDK 에 의존하지 않는 순수 함수만** 둔다.
 *
 * 그리는 일(CustomOverlay·Marker)은 clustering.ts 가, 묶는 일은 여기가 한다.
 * 나눠 둔 이유는 두 가지다.
 *  - 대표 좌표 방식을 바꿀 때(평균 → 정적 행정구역 중심) regionCenter 하나만 갈아 끼우면 된다
 *  - 레벨 경계를 실데이터로 조정할 때 상수만 보면 된다
 *
 * 설계 배경은 docs/map-clustering.md 참고.
 */

/**
 * 레벨 → 군집 단위 경계. 카카오 지도는 **숫자가 클수록 축소**다(1 = 최대 확대).
 *
 * 초기 튜닝값이고 최종 정책이 아니다. 실제 화면에서 지도를 움직여 보고 조정한다.
 * 조정할 때 이 상수만 고치면 된다.
 */
export const CLUSTER_LEVELS = {
  /** 이 레벨 이상(더 축소)이면 시·도 단위 */
  province: 11,
  /** 이 레벨 이상이면 시·군·구 단위. province 보다 작아야 한다 */
  district: 7,
} as const;

/**
 * 군집으로 묶는 최소 주택 수.
 *
 * 1 이다. 이 기능은 화면 좌표가 가까운 핀을 묶는 일반 클러스터링이 아니라
 * **행정구역 자체가 탐색 단위**인 기능이다. 축소 상태에서는 "그 지역에 몇 곳이 있는가"가
 * 읽혀야 하므로, 주택이 하나뿐인 지역도 `강남구 1` 오버레이로 보여 준다.
 * 2 이상으로 올리면 주택 1곳인 지역만 개별 핀으로 떨어져 나와
 * 같은 확대 수준에서 지역 단위와 주택 단위가 섞여 보인다.
 */
export const MIN_CLUSTER_SIZE = 1;

/** 주택이 한 곳뿐인 군집을 눌렀을 때 맞출 확대 수준. MapPanel 의 단일 핀 처리와 같은 값 */
export const SINGLE_PIN_LEVEL = 5;

export type RegionUnit = 'province' | 'district' | 'none';

export interface RegionCluster {
  /** 'province:서울특별시' / 'district:서울특별시 강남구'. 오버레이 재사용·추적용 */
  key: string;
  /** 화면에 쓰는 이름. 시·군·구 단위에서는 시군구만 쓴다(지도에 시도 맥락이 이미 보인다) */
  label: string;
  unit: 'province' | 'district';
  /** 대표 좌표 */
  lat: number;
  lng: number;
  /** 이 군집에 묶인 핀. 숫자 표시와 클릭 시 bounds 계산의 근거 */
  pins: MapPin[];
}

export interface RegionLayout {
  clusters: RegionCluster[];
  /** 개별 마커로 남길 핀. 지역 정보가 없는 주택이 여기로 온다 */
  loose: MapPin[];
}

/**
 * 사용자가 군집을 눌러 들어간 **탐색 단계**. 지도 전체에 똑같이 적용된다.
 *
 * <p>군집을 누르면 그 지역의 주택이 **모두 화면에 들어오도록** fitBounds 만 한다.
 * 그런데 강원처럼 넓은 지역은 범위에 맞춰도 레벨이 11 근처에 머물러 같은 시도 군집이 그대로 남는다.
 * 예전에는 레벨을 강제로 당겨서 풀었는데, 그러면 **fitBounds 가 보장한 범위가 깨져 주택이 화면 밖으로 밀려났다**
 * (원주 16곳 → 0곳). 그래서 지도 확대 수준은 건드리지 않고 표시 단계만 따로 기억한다.
 *
 * <p>⚠️ 이 값은 **누른 지역이 아니라 화면 전체**에 적용한다. 처음에는 누른 지역만 한 단계 펼쳤는데,
 * 그러면 같은 화면에서 경기만 시군구이고 서울·강원은 시도로 남아 단계가 섞여 보였다.
 * 역할을 나눈다 — **어디를 볼지는 fitBounds 가, 어떤 단계로 볼지는 이 값이** 정한다.
 */
export interface StageOverride {
  /** 들어간 단계. 시도보다 자세한 두 단계만 올 수 있다 */
  unit: Exclude<RegionUnit, 'province'>;
  /** 이 단계로 들어간 시점의 확대 수준(기준점). 그보다 축소하면 푼다 (releasedByZoom) */
  level: number;
}

/** 단계 깊이. 숫자가 클수록 더 자세한 단계다 */
const DEPTH: Record<RegionUnit, number> = { province: 0, district: 1, none: 2 };

/**
 * 확대 수준이 정한 기본 단계와 탐색 단계 중 **더 자세한 쪽**을 쓴다.
 *
 * 더 자세한 쪽을 쓰는 이유: 시군구 단계로 들어간 뒤 개별 핀이 보일 만큼 더 확대했는데
 * 탐색 단계 때문에 다시 시군구로 묶이면 확대가 먹히지 않는 것처럼 보인다.
 */
export function effectiveStage(base: RegionUnit, override: RegionUnit | null): RegionUnit {
  if (override == null) return base;
  return DEPTH[override] > DEPTH[base] ? override : base;
}

/** 지금 화면 전체를 그릴 단계. 탐색 단계가 없으면 기존 11/7 규칙 그대로다 */
export function effectiveUnit(level: number, override: StageOverride | null): RegionUnit {
  return effectiveStage(clusterUnit(level), override?.unit ?? null);
}

/**
 * 탐색 단계를 풀어야 하는지. **들어간 때보다 축소하면 푼다** — 규칙이 하나뿐이라 예측하기 쉽다.
 * 그 단계 안에서 확대하거나 움직이는 동안에는 계속 유지된다.
 */
export function releasedByZoom(override: StageOverride | null, level: number): boolean {
  return override != null && level > override.level;
}

/** 군집을 눌렀을 때 들어갈 다음 단계. 시도 → 시군구 → 개별 */
export function nextStage(unit: 'province' | 'district'): Exclude<RegionUnit, 'province'> {
  return unit === 'province' ? 'district' : 'none';
}

/** 확대 수준이 어느 행정구역 단위에 해당하는지. 'none' 이면 개별 핀을 그린다 */
export function clusterUnit(level: number): RegionUnit {
  if (level >= CLUSTER_LEVELS.province) return 'province';
  if (level >= CLUSTER_LEVELS.district) return 'district';
  return 'none';
}

/**
 * 군집 키. 묶을 수 없으면 null 을 돌려준다(= 개별 핀으로 남긴다).
 *
 * ⚠️ 시·군·구 키에 시도를 함께 넣는 이유: `중구`·`동구`·`남구` 같은 이름은 여러 시도에 중복으로 있다.
 * 시군구 이름만으로 묶으면 부산 중구와 대구 중구가 한 덩어리가 된다.
 * 표기는 백엔드가 쓰는 "시도 시군구" 형식(Candidate.regionKey)과 같다.
 */
export function regionKey(pin: MapPin, unit: RegionUnit): string | null {
  const province = blankToNull(pin.province);
  if (unit === 'province') return province == null ? null : `province:${province}`;
  if (unit === 'district') {
    const district = blankToNull(pin.district);
    if (province == null || district == null) return null;
    return `district:${province} ${district}`;
  }
  return null;
}

/**
 * 군집 대표 좌표. **지금은 묶인 주택 좌표의 산술 평균이다.**
 *
 * 추가 데이터 없이 현재 주택 데이터만으로 구할 수 있는 대신, 필터가 바뀌면 위치가 조금 움직인다.
 * MVP 에서는 이를 허용하기로 했다. 실제 화면에서 위치가 부자연스러우면
 * **이 함수만** 정적 행정구역 중심 좌표 조회로 바꾸면 된다(docs/map-clustering.md 방법 A).
 * 그래서 layoutPins 는 좌표 계산 방식을 모르게 떼어 두었다.
 */
export function regionCenter(pins: MapPin[]): { lat: number; lng: number } {
  const count = pins.length;
  let lat = 0;
  let lng = 0;
  for (const pin of pins) {
    lat += pin.lat;
    lng += pin.lng;
  }
  return { lat: lat / count, lng: lng / count };
}

/**
 * 핀 목록을 현재 표시 단계에 맞는 군집 + 개별 핀으로 나눈다.
 *
 * **불변식: clusters 의 핀 수 합 + loose 길이 = pins 길이.**
 * 지역 정보가 없다는 이유로 주택이 조용히 사라지면 안 된다(docs/map-clustering.md 6장).
 */
export function layoutPins(pins: MapPin[], level: number, override: StageOverride | null = null): RegionLayout {
  // 단계는 **화면 전체에 하나**다. 핀마다 따로 정하면 한 화면에 시도 군집과 시군구 군집이 섞인다.
  const unit = effectiveUnit(level, override);
  if (unit === 'none') return { clusters: [], loose: [...pins] };

  // 들어온 순서를 유지해야 같은 결과에서 군집 순서가 흔들리지 않는다(Map 은 삽입 순서를 지킨다).
  const grouped = new Map<string, MapPin[]>();
  const loose: MapPin[] = [];

  for (const pin of pins) {
    const key = regionKey(pin, unit);
    if (key == null) {
      loose.push(pin); // 지역 미상 — 개별 핀으로 남긴다
      continue;
    }
    const bucket = grouped.get(key);
    if (bucket) bucket.push(pin);
    else grouped.set(key, [pin]);
  }

  const clusters: RegionCluster[] = [];
  for (const [key, members] of grouped) {
    if (members.length < MIN_CLUSTER_SIZE) {
      loose.push(...members);
      continue;
    }
    const center = regionCenter(members);
    clusters.push({
      key,
      label: labelOf(members[0], unit),
      unit,
      lat: center.lat,
      lng: center.lng,
      pins: members,
    });
  }

  return { clusters, loose };
}

/**
 * 화면에 쓰는 짧은 시도명. **표시 전용이다** — 그룹 key 와 저장된 province/district 는 건드리지 않는다.
 * 매핑에 없는 값(새 행정구역 등)은 그대로 쓴다.
 */
const PROVINCE_SHORT: Record<string, string> = {
  서울특별시: '서울', 부산광역시: '부산', 대구광역시: '대구', 인천광역시: '인천',
  대전광역시: '대전', 울산광역시: '울산', 광주광역시: '광주', 세종특별자치시: '세종',
  경기도: '경기', 강원특별자치도: '강원', 충청북도: '충북', 충청남도: '충남',
  전북특별자치도: '전북', 전라남도: '전남', 경상북도: '경북', 경상남도: '경남',
  제주특별자치도: '제주', 전남광주통합특별시: '전남광주',
};

export function shortProvince(province: string): string {
  return PROVINCE_SHORT[province] ?? province;
}

/**
 * 시·도 단위는 시도 이름 그대로, 시·군·구 단위는 "짧은 시도 · 시군구".
 *
 * 시군구만 쓰면 `북구`·`동구`·`서구`·`남구`처럼 여러 시도에 같은 이름이 있어 화면에서 구분되지 않는다.
 * (그룹 key 는 원래부터 province 를 포함하고 있어 데이터는 정확히 갈려 있었다 — 라벨만의 문제였다.)
 */
function labelOf(pin: MapPin, unit: 'province' | 'district'): string {
  // regionKey 가 null 이 아니었으므로 두 값 모두 반드시 있다.
  if (unit === 'province') return pin.province as string;
  return `${shortProvince(pin.province as string)} · ${pin.district as string}`;
}

function blankToNull(value?: string | null): string | null {
  if (value == null) return null;
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}
