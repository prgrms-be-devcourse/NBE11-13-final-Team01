import { useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { FILTER_PARAM_KEYS, filterToSearch, paramsToFilter } from '../utils/filterParams';
import { EMPTY_FILTER, type HouseFilter } from '../types';

/** URL 쿼리 ↔ 필터. 화면을 오가도(지도 전체 ↔ 추천 ↔ 일정) 같은 필터가 유지된다. */
export function useHouseFilter({ omitStatuses = false }: { omitStatuses?: boolean } = {}) {
  const [params, setParams] = useSearchParams();
  const filter = useMemo(() => {
    const parsed = paramsToFilter(params);
    return omitStatuses ? { ...parsed, statuses: [...EMPTY_FILTER.statuses] } : parsed;
  }, [params, omitStatuses]);

  useEffect(() => {
    if (!omitStatuses || !params.has('statuses')) return;
    // 없어진 조작 항목이 옛 링크에 남아 지도 결과를 숨기지 않도록 해당 키만 정리한다.
    setParams((prev) => { const next = new URLSearchParams(prev); next.delete('statuses'); return next; }, { replace: true });
  }, [omitStatuses, params, setParams]);

  // 필터가 아닌 쿼리(지도의 mode, housing 등)는 그대로 둔다.
  // 예전처럼 통째로 덮어쓰면 필터를 건드릴 때마다 선택한 핀과 모드가 풀렸다.
  const setFilter = (next: HouseFilter) =>
    setParams(
      (prev) => {
        const merged = new URLSearchParams(filterToSearch(next));
        if (omitStatuses) merged.delete('statuses');
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
