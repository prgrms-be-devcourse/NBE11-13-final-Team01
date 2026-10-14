import { api, unwrap } from './client';
import { filterToParams } from './recommendation';
import type { FilterOptions, HouseFilter, HouseListResponse } from '../types';

/** 기본 필터링: 점수 없이 조건만으로 거른 전체 공급주택 */
export const houseApi = {
  list: (filter: HouseFilter, limit?: number) =>
    unwrap<HouseListResponse>(
      api.get('/api/v1/houses', { params: { ...filterToParams(filter), ...(limit ? { limit } : {}) } }),
    ),
  filterOptions: () => unwrap<FilterOptions>(api.get('/api/v1/houses/filter-options')),
};
