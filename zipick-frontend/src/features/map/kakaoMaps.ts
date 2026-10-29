export interface KakaoLatLng {
  getLat(): number;
  getLng(): number;
}

export interface KakaoLatLngBounds {
  extend(position: KakaoLatLng): void;
  getSouthWest(): KakaoLatLng;
  getNorthEast(): KakaoLatLng;
}

export interface KakaoMap {
  getCenter(): KakaoLatLng;
  setCenter(position: KakaoLatLng): void;
  setLevel(level: number): void;
  setMaxLevel(level: number): void;
  /** 확대 수준. 숫자가 **클수록 축소**다(1 = 최대 확대). 군집 단위를 고르는 기준이 된다 */
  getLevel(): number;
  setBounds(
    bounds: KakaoLatLngBounds,
    paddingTop?: number,
    paddingRight?: number,
    paddingBottom?: number,
    paddingLeft?: number,
  ): void;
  panTo(position: KakaoLatLng): void;
  getBounds(): KakaoLatLngBounds;
  relayout(): void;
}

export interface KakaoSize {
  _brand?: 'KakaoSize';
}

export interface KakaoPoint {
  _brand?: 'KakaoPoint';
}

export interface KakaoCustomOverlay {
  setMap(map: KakaoMap | null): void;
  setZIndex(zIndex: number): void;
}

export interface KakaoMarkerImage {
  _brand?: 'KakaoMarkerImage';
}

export interface KakaoMarker {
  setMap(map: KakaoMap | null): void;
  setImage(image: KakaoMarkerImage): void;
  setZIndex(zIndex: number): void;
  getPosition(): KakaoLatLng;
}

export interface KakaoRoadview {
  setPanoId(panoId: number, position: KakaoLatLng): void;
  relayout(): void;
}

export interface KakaoRoadviewClient {
  getNearestPanoId(position: KakaoLatLng, radius: number, callback: (panoId: number | null) => void): void;
}

export interface KakaoMapsApi {
  load(callback: () => void): void;
  Map: new (container: HTMLElement, options: { center: KakaoLatLng; level: number }) => KakaoMap;
  LatLng: new (latitude: number, longitude: number) => KakaoLatLng;
  LatLngBounds: new () => KakaoLatLngBounds;
  CustomOverlay: new (options: {
    position: KakaoLatLng;
    content: HTMLElement;
    clickable?: boolean;
    zIndex?: number;
  }) => KakaoCustomOverlay;
  Marker: new (options: {
    position: KakaoLatLng;
    title?: string;
    image?: KakaoMarkerImage;
    zIndex?: number;
    clickable?: boolean;
  }) => KakaoMarker;
  MarkerImage: new (
    src: string,
    size: KakaoSize,
    options?: { offset?: KakaoPoint },
  ) => KakaoMarkerImage;
  Size: new (width: number, height: number) => KakaoSize;
  Point: new (x: number, y: number) => KakaoPoint;
  Roadview: new (container: HTMLElement) => KakaoRoadview;
  RoadviewClient: new () => KakaoRoadviewClient;
  event: {
    addListener(target: object, event: string, handler: (...args: never[]) => void): void;
    removeListener(target: object, event: string, handler: (...args: never[]) => void): void;
  };
}

declare global {
  interface Window {
    kakao?: { maps: KakaoMapsApi };
  }
}

let sdkPromise: Promise<KakaoMapsApi> | null = null;

export function loadKakaoMaps(appKey: string): Promise<KakaoMapsApi> {
  if (window.kakao?.maps) {
    return new Promise((resolve) => window.kakao!.maps.load(() => resolve(window.kakao!.maps)));
  }

  if (sdkPromise) return sdkPromise;

  sdkPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.dataset.zipickKakaoMap = 'true';
    script.async = true;
    // 추가 라이브러리는 받지 않는다. 군집화는 행정구역 기준으로 직접 그리고(features/map/clustering),
    // 로드뷰는 코어 SDK 에 들어 있다.
    script.src =
      `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(appKey)}&autoload=false`;
    script.onload = () => {
      if (!window.kakao?.maps) {
        reject(new Error('카카오 지도 SDK를 불러왔지만 초기화하지 못했습니다.'));
        return;
      }
      window.kakao.maps.load(() => resolve(window.kakao!.maps));
    };
    script.onerror = () => reject(new Error('카카오 지도 SDK를 불러오지 못했습니다.'));
    document.head.appendChild(script);
  });

  return sdkPromise;
}
