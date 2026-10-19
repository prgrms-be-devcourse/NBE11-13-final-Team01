import { api, unwrap } from './client';

/**
 * 주변 생활 인프라 조회.
 * 서버가 배치로 미리 수집해 둔 값을 읽기만 하므로 이 요청이 카카오 API를 호출하지는 않는다.
 */

/** FRESH: 유효한 수집 결과 / STALE: 갱신 대기 중인 이전 결과 / PENDING: 아직 수집 전 / UNAVAILABLE: 좌표가 없어 제공 불가 */
export type InfraStatus = 'FRESH' | 'STALE' | 'PENDING' | 'UNAVAILABLE';

export interface InfraCategory {
  code: string;
  label: string;
  radiusMeter: number;
  nearestName: string;
  /** 카카오가 돌려준 직선거리(m). 실제 도보 경로가 아니다. */
  nearestDistanceMeter: number;
  /** 반경 내 검색된 해당 카테고리 전체 시설 수 */
  totalCount: number;
}

export interface HousingInfra {
  housingId: number;
  status: InfraStatus;
  collectedAt?: string | null;
  distanceBasis: 'STRAIGHT_LINE';
  /** 결과가 0건인 카테고리는 서버에서 빼고 내려준다 */
  categories: InfraCategory[];
}

export function getHousingInfra(housingId: number) {
  return unwrap(api.get(`/api/v1/map/housings/${housingId}/infra`)) as Promise<HousingInfra>;
}
