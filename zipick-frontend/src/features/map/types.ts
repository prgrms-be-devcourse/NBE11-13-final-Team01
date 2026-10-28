import type { MapHousingSummary } from '../../api/mapHousing';

/** 지도에 찍을 핀 하나. 추천 응답에서 좌표가 있는 집만 골라 만든다. */
export interface MapPin {
  housingId: number;
  title: string;
  address: string;
  lat: number;
  lng: number;
  /**
   * 행정구역별 군집화 기준. 서버가 정규화해 둔 값을 그대로 쓴다(주소 문자열을 쪼개지 않는다).
   * 전체 공고는 MapHousingSummary, 추천은 RecommendedHouse 가 같은 값을 내려준다.
   * 비어 있으면 군집에 묶지 않고 개별 핀으로 남긴다 — 조용히 사라지면 안 된다.
   */
  province?: string | null;
  district?: string | null;
  /**
   * 같은 좌표에 겹친 주택을 다룰 때 쓰는 식별 정보 (features/map/locationGroup).
   * ⚠️ noticeId 는 **공고** id 다. 같은 건물이라도 공고가 다르면 서로 다른 신청 기회이므로
   * 절대 하나로 합치면 안 된다. 관심 공고 저장에 쓰는 id 와 같은 값이며 housingId 와 다르다.
   */
  noticeId?: number | null;
  rentalType?: string | null;
  status?: string | null;
  /**
   * 같은 위치 목록 카드에서 **서로 다른 신청 기회**임을 알아볼 수 있게 하는 값들.
   * 제목·기관·유형까지 같은 공고가 여러 건 올라오는 경우가 있어 공고일이 유일한 구분자일 수 있다.
   */
  noticeTitle?: string | null;
  agencyName?: string | null;
  announcedAt?: string | null;
  applyEndAt?: string | null;
}

/** 지도 패널이 받는 props. 지도 담당자는 이 계약만 지키면 된다. */
export interface MapPanelProps {
  pins: MapPin[];
  selectedId?: number | null;
  /**
   * 우측에 "이 위치의 주택 N개" 목록이 열려 있는 위치의 주택 id 들.
   * 그 위치 마커를 선택 상태로 보여 주기 위한 것이며, 목록을 닫을 때만 비운다.
   */
  selectedLocationIds?: number[] | null;
  /**
   * 행정구역 군집 클릭·'전국 보기'처럼 **새 탐색을 시작**했을 때 호출된다.
   * 이전에 열어 둔 위치 목록·주택 상세를 닫으라는 신호다.
   */
  onExploreStart?: () => void;
  /** 이 값이 바뀌면 펼쳐 둔 지역 상태를 푼다. 모드 전환처럼 탐색 맥락이 통째로 바뀔 때 쓴다 */
  viewResetKey?: string | number;
  onSelect?: (housingId: number) => void;
  /**
   * 같은 좌표에 주택이 둘 이상 겹쳐 있는 위치를 눌렀을 때. 겹친 주택 id 를 순서대로 넘긴다.
   * 핀 하나짜리 위치는 onSelect 로 간다(기존 동작 그대로).
   */
  onSelectLocation?: (housingIds: number[]) => void;
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
    province: housing.province,
    district: housing.district,
    noticeId: housing.noticeId,
    rentalType: housing.rentalType,
    status: housing.status,
    noticeTitle: housing.noticeTitle,
    agencyName: housing.agencyName,
    announcedAt: housing.noticeAnnouncedAt,
    applyEndAt: housing.applyEndAt,
  }));
}
