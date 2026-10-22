import { api, unwrap } from './client';
import { filterToParams } from './recommendation';
import type { FilterOptions, HouseFilter, HouseListResponse } from '../types';

/**
 * 기본 필터링: 점수 없이 조건만으로 거른 전체 공급주택.
 *
 * 지도는 주택 단위 전용 API(/api/v1/map/housings)로 옮겨 가 list() 를 쓰지 않는다.
 * 다만 엔드포인트와 응답 계약은 그대로 살아 있어(추천 담당 영역) 함수는 남겨 둔다.
 * filterOptions() 는 필터 칩과 온보딩 지역 선택에서 계속 쓴다.
 */
export const houseApi = {
  list: (filter: HouseFilter, limit?: number) =>
    unwrap<HouseListResponse>(
      api.get('/api/v1/houses', { params: { ...filterToParams(filter), ...(limit ? { limit } : {}) } }),
    ),
  filterOptions: () => unwrap<FilterOptions>(api.get('/api/v1/houses/filter-options')),
};
