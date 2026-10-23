import type {
  KakaoCluster,
  KakaoMap,
  KakaoMapsApi,
  KakaoMarker,
  KakaoMarkerClusterer,
  KakaoMarkerImage,
} from './kakaoMaps';
import type { MapPin } from './types';

/**
 * 지도 핀 군집화. 카카오 지도 SDK 의 기본 MarkerClusterer 를 쓴다(일반 클러스터링).
 *
 * MapPanel 의 다른 기능(지도 생성·로드뷰·선택 상태)과 분리해 두었다.
 * 군집 방식을 바꿀 때 이 파일만 갈아 끼우면 되도록 한 것이다.
 *
 * TODO(행정구역별 클러스터링): 지금은 화면 좌표가 가까운 핀을 묶기만 해서
 *   군집에 "12" 같은 숫자만 뜬다. 최종적으로는 전국에서 시도별, 중간 확대에서 시군구별로 묶고
 *   "강남구 8곳"처럼 지역명과 함께 보여 주려 한다.
 *   필요한 데이터·구현 단계·수정 지점은 docs/map-clustering.md 에 정리해 두었다.
 *   교체할 때는 이 파일의 createPinLayer 가 돌려주는 계약(render/clear/findMarker)만 지키면 된다.
 */

/** 군집 숫자 구간. 자리수가 늘어날 때마다 다음 단계 스타일을 쓴다. */
const CLUSTER_THRESHOLDS = [10, 50, 200];

/** ZIPICK 의 흰색·파랑 톤에 맞춘 군집 원. 숫자는 고유 주택 수다. */
const CLUSTER_STYLES = [36, 44, 52, 60].map((size, index) => ({
  width: `${size}px`,
  height: `${size}px`,
  background: index === 0 ? 'rgba(37, 99, 235, 0.85)' : 'rgba(29, 78, 216, 0.9)',
  border: '2px solid #ffffff',
  borderRadius: '999px',
  color: '#ffffff',
  textAlign: 'center',
  fontWeight: '700',
  fontSize: `${12 + index}px`,
  lineHeight: `${size - 4}px`,
  boxShadow: '0 2px 8px rgba(17, 24, 39, 0.25)',
}));

const PIN_SVG = (fill: string, stroke: string) =>
  `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="36" viewBox="0 0 28 36">` +
      `<path d="M14 1C7.4 1 2 6.3 2 12.9 2 21.4 14 35 14 35s12-13.6 12-22.1C26 6.3 20.6 1 14 1z" ` +
      `fill="${fill}" stroke="${stroke}" stroke-width="2"/>` +
      `<circle cx="14" cy="13" r="4.5" fill="#ffffff"/></svg>`,
  )}`;

export interface PinLayer {
  /** 핀 목록을 통째로 교체한다. 이전 마커는 지도와 군집에서 모두 떼어 낸다. */
  render(pins: MapPin[]): void;
  /** 선택한 핀만 강조한다. 지도 중심은 건드리지 않는다. */
  highlight(housingId: number | null): void;
  /** 지도에서 모두 떼어 내고 자원을 정리한다. */
  destroy(): void;
}

interface Options {
  maps: KakaoMapsApi;
  map: KakaoMap;
  onSelect: (housingId: number) => void;
}

/**
 * 핀 + 군집 레이어를 만든다. 지도 인스턴스는 바깥(MapPanel)이 들고 있고,
 * 여기서는 마커만 올렸다 내린다. 모드나 필터가 바뀌어도 지도는 다시 만들지 않는다.
 */
export function createPinLayer({ maps, map, onSelect }: Options): PinLayer {
  const normalImage = new maps.MarkerImage(PIN_SVG('#2563eb', '#1d4ed8'), new maps.Size(28, 36), {
    offset: new maps.Point(14, 35),
  });
  const selectedImage = new maps.MarkerImage(PIN_SVG('#dc2626', '#b91c1c'), new maps.Size(34, 44), {
    offset: new maps.Point(17, 43),
  });

  // clusterer 라이브러리를 못 받은 경우에도 핀은 그대로 보여야 한다.
  const clusterer: KakaoMarkerClusterer | null = maps.MarkerClusterer
    ? new maps.MarkerClusterer({
        map,
        averageCenter: true,
        // 레벨이 이보다 작아지면(확대) 군집을 풀고 개별 핀을 보여 준다.
        minLevel: 6,
        minClusterSize: 2,
        // 군집 클릭은 우리가 직접 처리한다(그 영역으로 확대).
        disableClickZoom: true,
        calculator: CLUSTER_THRESHOLDS,
        styles: CLUSTER_STYLES,
      })
    : null;

  const markers = new Map<number, KakaoMarker>();
  const listeners: { target: object; event: string; handler: (...args: never[]) => void }[] = [];

  const listen = (target: object, event: string, handler: (...args: never[]) => void) => {
    maps.event.addListener(target, event, handler);
    listeners.push({ target, event, handler });
  };

  if (clusterer) {
    // 군집을 누르면 그 군집이 차지한 범위로 한 단계 확대한다.
    // 사용자가 직접 누른 경우에만 지도를 움직인다(필터 변경 때는 움직이지 않는다).
    listen(clusterer, 'clusterclick', ((cluster: KakaoCluster) => {
      map.setCenter(cluster.getCenter());
      map.setLevel(Math.max(1, currentLevel(map) - 2));
    }) as unknown as (...args: never[]) => void);
  }

  const clearMarkers = () => {
    const current = [...markers.values()];
    if (clusterer) clusterer.removeMarkers(current, true);
    current.forEach((marker) => marker.setMap(null));
    markers.clear();
  };

  return {
    render(pins) {
      clearMarkers();
      const created: KakaoMarker[] = [];
      for (const pin of pins) {
        const marker = new maps.Marker({
          position: new maps.LatLng(pin.lat, pin.lng),
          title: `${pin.title} · ${pin.address}`,
          image: normalImage,
          clickable: true,
        });
        listen(marker, 'click', (() => onSelect(pin.housingId)) as unknown as (...args: never[]) => void);
        markers.set(pin.housingId, marker);
        created.push(marker);
      }
      if (clusterer) {
        clusterer.clear();
        clusterer.addMarkers(created);
      } else {
        created.forEach((marker) => marker.setMap(map));
      }
    },

    highlight(housingId) {
      markers.forEach((marker, id) => {
        const selected = id === housingId;
        marker.setImage(selected ? selectedImage : normalImage);
        marker.setZIndex(selected ? 10 : 1);
      });
    },

    destroy() {
      clearMarkers();
      clusterer?.clear();
      clusterer?.setMap(null);
      listeners.forEach(({ target, event, handler }) => maps.event.removeListener(target, event, handler));
      listeners.length = 0;
    },
  };
}

/** 지도 레벨을 읽는다. SDK 타입에 getLevel 이 없어 안전하게 꺼낸다. */
function currentLevel(map: KakaoMap): number {
  const candidate = map as unknown as { getLevel?: () => number };
  return typeof candidate.getLevel === 'function' ? candidate.getLevel() : 6;
}

/** 선택한 핀을 알 수 없는 마커 이미지 */
export type { KakaoMarkerImage };
