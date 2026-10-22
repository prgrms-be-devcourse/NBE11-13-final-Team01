import type { MapHousingSupply } from '../api/mapHousing';
import type { HouseFilter, HousingType } from '../types';

/**
 * 지도에서 공급 조건 필터를 걸었을 때, 상세 패널의 어느 공급 유형이 그 조건을 만족하는지 판정한다.
 * "왜 이 주택이 검색 결과에 들어왔는지"를 보여 주기 위한 것이다.
 *
 * ⚠️ 서버 판정(HouseFilterPredicates.supplyScope)을 프론트에서 따라 한 코드다.
 * 둘이 어긋나면 지도에는 나오는데 상세에서는 일치 표시가 없는 이상한 화면이 된다.
 * 서버 쪽 규칙이 바뀌면 여기도 함께 고쳐야 한다. 맞춰 둔 지점은 다음과 같다.
 *
 * | 조건       | 서버                                             | 여기                                    |
 * | ---------- | ------------------------------------------------ | --------------------------------------- |
 * | 보증금·월세 | `rentDeposit <= depositMax * 10000` (원 단위 컬럼) | 같은 식. 상세 응답도 원 단위다          |
 * | 전용면적   | `exclusiveArea >= areaMin`                        | 같은 식                                 |
 * | 주택 유형  | `housingType in HousingType.rawNames` (완전 일치)  | 아래 RAW_NAMES 로 같은 비교             |
 * | 값 없음    | 지도는 엄격 — null 이면 불충족                     | 같음                                    |
 * | 활성 여부  | `housingSupply.active = true`                     | 상세 API 가 활성 호실만 돌려줘 따로 안 봄 |
 *
 * 임대유형(rentalTypes)·지역·기관·모집 상태는 공고에서 판정하므로 여기서 보지 않는다.
 */

/** 백엔드 HousingType.rawNames 와 같은 값. 완전 일치로 비교한다. */
const RAW_NAMES: Record<HousingType, string[]> = {
  APARTMENT: ['아파트'],
  MULTI_HOUSEHOLD: ['다세대주택', '다가구주택', '다세대', '다가구'],
  ROW_HOUSE: ['연립주택', '연립'],
  OFFICETEL: ['오피스텔'],
  URBAN_HOUSING: ['도시형생활주택'],
};

const MANWON = 10_000;

/** 사용자가 공급 정보 기반 조건을 하나라도 골랐는지. 고르지 않았으면 강조할 대상이 없다. */
export function hasSupplyConditions(filter: HouseFilter): boolean {
  return (
    filter.depositMin != null ||
    filter.depositMax != null ||
    filter.rentMin != null ||
    filter.rentMax != null ||
    filter.areaMin != null ||
    filter.areaMax != null ||
    filter.housingTypes.length > 0
  );
}

/**
 * 이 공급 유형 **하나가** 고른 조건을 모두 만족하는가.
 * 서버와 같이 레코드 단위로 판정한다. 다른 공급 유형의 값을 끌어오지 않는다.
 */
export function matchesSupplyConditions(supply: MapHousingSupply, filter: HouseFilter): boolean {
  if (filter.housingTypes.length > 0) {
    const allowed = filter.housingTypes.flatMap((type) => RAW_NAMES[type] ?? []);
    if (!supply.housingType || !allowed.includes(supply.housingType)) return false;
  }
  if (!inRange(supply.rentDeposit, filter.depositMin, filter.depositMax)) return false;
  if (!inRange(supply.monthlyRent, filter.rentMin, filter.rentMax)) return false;
  if (!inAreaRange(supply.exclusiveArea, filter.areaMin, filter.areaMax)) return false;
  return true;
}

/** 금액(원 단위 값)을 만 원 단위 조건과 비교한다. 값이 없으면 고른 조건을 만족하지 못한 것이다. */
function inRange(valueInWon: number | null | undefined, minManwon?: number, maxManwon?: number): boolean {
  if (minManwon == null && maxManwon == null) return true;
  if (valueInWon == null) return false;
  if (minManwon != null && valueInWon < minManwon * MANWON) return false;
  if (maxManwon != null && valueInWon > maxManwon * MANWON) return false;
  return true;
}

function inAreaRange(area: number | null | undefined, min?: number, max?: number): boolean {
  if (min == null && max == null) return true;
  if (area == null) return false;
  if (min != null && area < min) return false;
  if (max != null && area > max) return false;
  return true;
}

/** 조건을 만족하는 공급 유형을 앞으로 보낸다. 나머지도 그대로 둬 전체를 확인할 수 있다. */
export function sortByMatch(supplies: MapHousingSupply[], filter: HouseFilter): MapHousingSupply[] {
  if (!hasSupplyConditions(filter)) return supplies;
  return [...supplies].sort((a, b) => {
    const am = matchesSupplyConditions(a, filter) ? 0 : 1;
    const bm = matchesSupplyConditions(b, filter) ? 0 : 1;
    return am - bm;
  });
}
