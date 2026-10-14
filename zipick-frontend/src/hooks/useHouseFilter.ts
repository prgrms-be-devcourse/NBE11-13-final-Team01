import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { filterToSearch, paramsToFilter } from '../utils/filterParams';
import type { HouseFilter } from '../types';

/** URL 쿼리 ↔ 필터. 화면을 오가도(추천 ↔ 전체 ↔ 지도) 같은 필터가 유지된다. */
export function useHouseFilter() {
  const [params, setParams] = useSearchParams();
  const filter = useMemo(() => paramsToFilter(params), [params]);
  const setFilter = (f: HouseFilter) => setParams(filterToSearch(f), { replace: true });
  return { filter, setFilter, search: params.toString() };
}
