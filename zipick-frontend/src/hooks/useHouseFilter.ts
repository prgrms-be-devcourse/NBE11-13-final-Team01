import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { FILTER_PARAM_KEYS, filterToSearch, paramsToFilter } from '../utils/filterParams';
import type { HouseFilter } from '../types';

/** URL 쿼리 ↔ 필터. 화면을 오가도(지도 전체 ↔ 추천 ↔ 일정) 같은 필터가 유지된다. */
export function useHouseFilter() {
  const [params, setParams] = useSearchParams();
  const filter = useMemo(() => paramsToFilter(params), [params]);

  // 필터가 아닌 쿼리(지도의 mode, housing 등)는 그대로 둔다.
  // 예전처럼 통째로 덮어쓰면 필터를 건드릴 때마다 선택한 핀과 모드가 풀렸다.
  const setFilter = (next: HouseFilter) =>
    setParams(
      (prev) => {
        const merged = new URLSearchParams(filterToSearch(next));
        prev.forEach((value, key) => {
          if (!merged.has(key) && !(FILTER_PARAM_KEYS as readonly string[]).includes(key)) {
            merged.set(key, value);
          }
        });
        return merged;
      },
      { replace: true },
    );

  return { filter, setFilter, search: params.toString() };
}
