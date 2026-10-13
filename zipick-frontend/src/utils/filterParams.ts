import { EMPTY_FILTER, type HouseFilter } from '../types';

// 필터 ↔ URL 쿼리. 추천·지도·캘린더 화면이 같은 쿼리를 공유해 서로 오가도 필터가 유지된다.

export function paramsToFilter(p: URLSearchParams): HouseFilter {
  const list = (k: string) => (p.get(k) ? p.get(k)!.split(',').filter(Boolean) : []);
  const num = (k: string) => (p.get(k) ? Number(p.get(k)) : undefined);
  const statuses = list('statuses') as HouseFilter['statuses'];
  return {
    regions: list('regions'),
    depositMin: num('depositMin'),
    depositMax: num('depositMax'),
    rentMin: num('rentMin'),
    rentMax: num('rentMax'),
    areaMin: num('areaMin'),
    areaMax: num('areaMax'),
    housingTypes: list('housingTypes') as HouseFilter['housingTypes'],
    statuses: statuses.length ? statuses : EMPTY_FILTER.statuses,
    agencies: list('agencies') as HouseFilter['agencies'],
    rentalTypes: list('rentalTypes') as HouseFilter['rentalTypes'],
    includeUnknown: p.get('includeUnknown') !== 'false',
  };
}

export function filterToSearch(f: HouseFilter): Record<string, string> {
  const out: Record<string, string> = {};
  const set = (k: string, v: string[] | number | undefined) => {
    if (Array.isArray(v)) {
      if (v.length) out[k] = v.join(',');
    } else if (v !== undefined && !Number.isNaN(v)) out[k] = String(v);
  };
  set('regions', f.regions);
  set('depositMin', f.depositMin);
  set('depositMax', f.depositMax);
  set('rentMin', f.rentMin);
  set('rentMax', f.rentMax);
  set('areaMin', f.areaMin);
  set('areaMax', f.areaMax);
  set('housingTypes', f.housingTypes);
  set('statuses', f.statuses);
  set('agencies', f.agencies);
  set('rentalTypes', f.rentalTypes);
  if (!f.includeUnknown) out.includeUnknown = 'false';
  return out;
}
