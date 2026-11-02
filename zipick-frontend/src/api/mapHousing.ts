import { api, unwrap } from './client';
import { filterToParams } from './recommendation';
import type { AnnouncementStatus, HouseFilter } from '../types';

/**
 * 지도 핀 하나. 백엔드 MapHousingSummaryResponse 와 1:1 이며 **14개 필드뿐**이다.
 * 보증금·면적 같은 공급 정보는 들어 있지 않다(상세 조회에서 받는다).
 * 좌표가 없는 주택은 서버가 조회 단계에서 빼므로 위도·경도는 항상 있다.
 *
 * province/district 는 행정구역별 군집화가 쓴다. 주소 문자열을 프론트에서 쪼개지 않고
 * 서버가 정규화해 둔 값을 그대로 받는다. 주소 정규화가 안 된 주택은 비어 있을 수 있어
 * optional 이며, 그런 주택은 군집에 묶이지 않고 개별 핀으로 남는다(features/map/regionCluster).
 */
export interface MapHousingSummary {
  housingId: number;
  name?: string | null;
  address: string;
  province?: string | null;
  district?: string | null;
  latitude: number;
  longitude: number;
  /** 공고 id. 같은 좌표에 겹친 주택을 공고 단위로 구분할 때 쓴다 (features/map/locationGroup).
   *  ⚠️ 관심 공고 저장에 쓰는 id 와 같은 값이다. housingId 와 혼동하지 말 것. */
  noticeId: number;
  noticeTitle: string;
  /** 공고일 (YYYY-MM-DD). 같은 건물에 반복 올라온 공고를 구분하는 유일한 값일 때가 있다 */
  noticeAnnouncedAt?: string | null;
  /** 접수 마감일 (YYYY-MM-DD). 공고에 일정이 없으면 null */
  applyEndAt?: string | null;
  agencyName?: string | null;
  rentalType?: string | null;
  status: AnnouncementStatus;
}

export interface MapHousingSupply {
  housingSupplyId: number;
  housingType?: string | null;
  supplyType?: string | null;
  buildingDong?: string | null;
  unitNumber?: string | null;
  floor?: number | null;
  roomCount?: number | null;
  exclusiveArea?: number | null;
  supplyCount?: number | null;
  supplyHouseCount?: number | null;
  rentDeposit?: number | null;
  monthlyRent?: number | null;
}

export interface MapHousingAttachment {
  attachmentId: number;
  attachmentType: string;
  fileName?: string | null;
  sourceUrl: string;
}

export interface MapHousingMedia {
  mediaId: number;
  mediaType: 'BUILDING_PHOTO' | 'FLOOR_PLAN' | 'SITE_PLAN' | string;
  fileName?: string | null;
  sourceUrl: string;
}

export interface MapHousingDetail {
  housingId: number;
  name?: string | null;
  address: string;
  normalizedAddress?: string | null;
  /** 좌표를 아직 못 구한 주택도 상세는 열린다(핀 목록과 달리 좌표 조건이 없다). 로드뷰는 이때 숨긴다. */
  latitude: number | null;
  longitude: number | null;
  buildYear?: number | null;
  floor?: number | null;
  heatingMethod?: string | null;
  totalHouseholdCount?: number | null;
  noticeId: number;
  noticeTitle: string;
  noticeType?: string | null;
  rentalType?: string | null;
  /** 공개 상세는 접수 중(OPEN)과 공고 예정(UPCOMING)을 모두 내려준다. */
  status: AnnouncementStatus;
  /** 접수 시작·마감일(한국 시간 기준 날짜). 공고에 일정이 없으면 null */
  applyStartAt?: string | null;
  applyEndAt?: string | null;
  agencyName?: string | null;
  detailUrl?: string | null;
  supplies: MapHousingSupply[];
  attachments: MapHousingAttachment[];
  media: MapHousingMedia[];
}

export interface MapHousingList {
  total: number;
  housings: MapHousingSummary[];
}

/**
 * 지도에 찍을 공급주택. 공고 예정 + 접수 중을 주택 단위로 돌려준다.
 *
 * 모집 상태는 보내지 않고 서버의 공개 기본 범위를 사용한다. 나머지 필터는 기존 매핑을 쓴다.
 * 서버의 지도 전용 정책에 따라
 * - 마감·취소 공고는 표시하지 않고
 * - 고른 공급 조건의 값이 없는 호실은 조건 불충족으로 보며(includeUnknown 으로 우회 불가)
 * - 보증금·면적 같은 공급 조건은 **같은 활성 호실 하나**가 모두 만족해야 주택이 포함된다.
 */
export function getMapHousings(filter: HouseFilter) {
  const params = filterToParams(filter);
  // 모집 상태 선택은 제거했다. 서버의 기존 공개 기본 범위(UPCOMING+OPEN)를 사용한다.
  delete params.statuses;
  return unwrap(api.get('/api/v1/map/housings', { params })) as Promise<MapHousingList>;
}

export function getMapHousingDetail(housingId: number) {
  return unwrap(api.get(`/api/v1/map/housings/${housingId}`)) as Promise<MapHousingDetail>;
}
