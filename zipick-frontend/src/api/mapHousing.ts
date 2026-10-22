import { api, unwrap } from './client';
import type { AnnouncementStatus } from '../types';

export interface MapHousingSummary {
  housingId: number;
  name?: string | null;
  address: string;
  latitude: number;
  longitude: number;
  noticeTitle: string;
  agencyName?: string | null;
  rentalType?: string | null;
  status: 'OPEN';
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

export function getOpenMapHousings() {
  return unwrap(api.get('/api/v1/map/housings')) as Promise<MapHousingList>;
}

export function getMapHousingDetail(housingId: number) {
  return unwrap(api.get(`/api/v1/map/housings/${housingId}`)) as Promise<MapHousingDetail>;
}
