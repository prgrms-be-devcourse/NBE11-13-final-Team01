import type { RecommendedHouse } from '../../types';

/** 지도에 찍을 핀 하나. 추천 응답에서 좌표가 있는 집만 골라 만든다. */
export interface MapPin {
  houseId: number;
  announcementId: number;
  title: string;
  address: string;
  lat: number;
  lng: number;
  /** 동 단위 근사 좌표면 true — 핀을 흐리게 그리거나 반경 원으로 표시 */
  approximate: boolean;
  score: number;
  rank: number;
  deposit?: number;
  monthlyRent?: number;
  status: RecommendedHouse['status'];
  closeDate?: string;
}

/** 지도 패널이 받는 props. 지도 담당자는 이 계약만 지키면 된다. */
export interface MapPanelProps {
  pins: MapPin[];
  selectedId?: number | null;
  onSelect?: (houseId: number) => void;
  /** 지도를 움직여 보이는 영역이 바뀌면 호출. 보이는 범위로 목록을 좁히고 싶을 때 쓴다 */
  onBoundsChange?: (bounds: { swLat: number; swLng: number; neLat: number; neLng: number }) => void;
  className?: string;
}

export function toMapPins(houses: RecommendedHouse[]): MapPin[] {
  return houses
    .map((h, i) => ({ h, rank: i + 1 }))
    .filter(({ h }) => h.lat != null && h.lng != null)
    .map(({ h, rank }) => ({
      houseId: h.houseId,
      announcementId: h.announcementId,
      title: h.title,
      address: h.address,
      lat: h.lat!,
      lng: h.lng!,
      approximate: h.approximate,
      score: h.score,
      rank,
      deposit: h.deposit,
      monthlyRent: h.monthlyRent,
      status: h.status,
      closeDate: h.closeDate,
    }));
}
