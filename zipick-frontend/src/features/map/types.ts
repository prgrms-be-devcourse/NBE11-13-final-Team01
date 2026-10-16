import type { MapHousingSummary } from '../../api/mapHousing';

/** 지도에 찍을 핀 하나. 추천 응답에서 좌표가 있는 집만 골라 만든다. */
export interface MapPin {
  housingId: number;
  title: string;
  address: string;
  lat: number;
  lng: number;
}

/** 지도 패널이 받는 props. 지도 담당자는 이 계약만 지키면 된다. */
export interface MapPanelProps {
  pins: MapPin[];
  selectedId?: number | null;
  onSelect?: (housingId: number) => void;
  /** 지도를 움직여 보이는 영역이 바뀌면 호출. 보이는 범위로 목록을 좁히고 싶을 때 쓴다 */
  onBoundsChange?: (bounds: { swLat: number; swLng: number; neLat: number; neLng: number }) => void;
  className?: string;
}

export function toMapPins(housings: MapHousingSummary[]): MapPin[] {
  return housings.map((housing) => ({
    housingId: housing.housingId,
    title: housing.name || housing.noticeTitle,
    address: housing.address,
    lat: housing.latitude,
    lng: housing.longitude,
  }));
}
