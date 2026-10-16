import { api, unwrap } from './client';

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
  latitude: number;
  longitude: number;
  buildYear?: number | null;
  floor?: number | null;
  heatingMethod?: string | null;
  totalHouseholdCount?: number | null;
  noticeId: number;
  noticeTitle: string;
  noticeType?: string | null;
  rentalType?: string | null;
  status: 'OPEN';
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
