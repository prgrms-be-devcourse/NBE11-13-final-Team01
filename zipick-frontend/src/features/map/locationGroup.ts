import type { MapPin } from './types';

/**
 * 같은 좌표에 겹친 주택 처리. **카카오 SDK 에 의존하지 않는 순수 함수만** 둔다.
 *
 * 수집 데이터에는 같은 건물이 여러 Housing 행으로 들어오는 경우가 있다. 두 가지가 섞여 있다.
 *  ① 공고가 달라서 — 같은 건물이라도 **서로 다른 신청 기회**다. 합치면 안 된다
 *  ② 같은 공고 안에서 주소 표기만 다르게 수집돼서 — 사용자에게는 중복으로 보인다
 *
 * 마커는 Housing 마다 만들어지지만 좌표가 같으면 화면에서 완전히 겹쳐 하나처럼 보인다.
 * 그래서 개별 핀 단계에서는 **좌표로 먼저 묶고**, 그 안에서만 아주 보수적으로 중복을 줄인다.
 *
 * ⚠️ **false merge 보다 중복 표시를 택한다.** 하나라도 다를 수 있으면 합치지 않는다.
 */

/** 좌표가 같은 주택 묶음. 지도에 위치 하나로 그린다 */
export interface LocationGroup {
  /** 'lat,lng' */
  key: string;
  lat: number;
  lng: number;
  /** 표시 대상. 중복으로 판정돼 숨긴 주택은 여기 없다 */
  pins: MapPin[];
  /** 중복으로 묶여 숨긴 주택. 개수 검산·디버깅용이며 화면에는 쓰지 않는다 */
  merged: MapPin[];
}

/** 필드 구분자. 주소·공고명에 나올 일이 없는 제어문자를 쓴다 */
const SEP = '␟';

/**
 * 같은 주택으로 볼 수 있는지 판정하는 지문.
 *
 * 지도 응답에 있는 값만 쓴다. 포함한 이유는 아래와 같다.
 *  - noticeId  공고가 다르면 다른 신청 기회다. **가장 중요한 분리 기준**
 *  - title     주택명(없으면 공고명). 사용자가 카드에서 가장 먼저 보는 값
 *  - address   같은 좌표라도 주소가 다르면 다른 동·호이거나 수집 표기가 다른 것이다.
 *              어느 쪽인지 지도 응답만으로는 알 수 없으므로 **다르면 합치지 않는다**
 *  - rentalType / status  임대 유형·모집 상태가 다르면 당연히 다른 항목이다
 *
 * 보증금·면적·동·호 같은 공급 조건은 housing_supply 쪽 값이라 지도 응답에 없다.
 * 그래서 "같아 보이는데 공급 조건만 다른" 경우를 구분할 수 없고, 그만큼 더 보수적으로 본다.
 */
export function fingerprint(pin: MapPin): string {
  return [
    pin.noticeId ?? '',
    pin.title ?? '',
    pin.address ?? '',
    pin.rentalType ?? '',
    pin.status ?? '',
  ].join(SEP);
}

/** 판정에 쓸 값이 부족해 "같다"고 말할 수 없는 핀인지. 이런 핀은 절대 합치지 않는다 */
export function cannotCompare(pin: MapPin): boolean {
  return pin.noticeId == null || !pin.address;
}

export function locationKey(pin: MapPin): string {
  return `${pin.lat},${pin.lng}`;
}

/**
 * 핀을 좌표로 묶고, 묶음 안에서만 보수적으로 중복을 줄인다.
 *
 * **불변식: 모든 group 의 pins + merged 길이 합 = 입력 핀 수.** 주택이 조용히 사라지지 않는다.
 * 좌표(lat/lng)가 유한한 값이 아닌 핀은 애초에 지도에 올릴 수 없어 제외하지 않고 각자 자기 위치를 갖는다.
 */
export function groupByLocation(pins: MapPin[]): LocationGroup[] {
  // 입력 순서를 유지해야 같은 데이터에서 결과가 흔들리지 않는다.
  const buckets = new Map<string, MapPin[]>();
  for (const pin of pins) {
    const key = locationKey(pin);
    const bucket = buckets.get(key);
    if (bucket) bucket.push(pin);
    else buckets.set(key, [pin]);
  }

  const groups: LocationGroup[] = [];
  for (const [key, members] of buckets) {
    const { kept, merged } = dedupe(members);
    groups.push({ key, lat: members[0].lat, lng: members[0].lng, pins: kept, merged });
  }
  return groups;
}

/**
 * 좌표가 같은 주택들 중 **모든 비교 가능 필드가 똑같은** 것만 하나로 줄인다.
 * 대표는 housingId 가 가장 작은 주택이다(입력 순서가 바뀌어도 결과가 같게).
 */
function dedupe(members: MapPin[]): { kept: MapPin[]; merged: MapPin[] } {
  if (members.length === 1) return { kept: members, merged: [] };

  const byPrint = new Map<string, MapPin[]>();
  const kept: MapPin[] = [];
  const merged: MapPin[] = [];

  for (const pin of members) {
    if (cannotCompare(pin)) {
      kept.push(pin); // 판정 근거가 부족하다 → 그대로 둔다
      continue;
    }
    const print = fingerprint(pin);
    const same = byPrint.get(print);
    if (same) same.push(pin);
    else byPrint.set(print, [pin]);
  }

  for (const same of byPrint.values()) {
    if (same.length === 1) {
      kept.push(same[0]);
      continue;
    }
    const sorted = [...same].sort((a, b) => a.housingId - b.housingId);
    kept.push(sorted[0]);
    merged.push(...sorted.slice(1));
  }

  // 지도에 그리는 순서를 housingId 로 고정한다(마커 z-index·목록 순서가 매번 달라지지 않게).
  kept.sort((a, b) => a.housingId - b.housingId);
  return { kept, merged };
}
