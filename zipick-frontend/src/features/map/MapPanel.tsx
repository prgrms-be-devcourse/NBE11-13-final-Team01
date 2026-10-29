import { useEffect, useRef, useState } from 'react';
import { createPinLayer, type PinLayer } from './clustering';
import { loadKakaoMaps } from './kakaoMaps';
import type { KakaoMap, KakaoMapsApi } from './kakaoMaps';
import type { MapPanelProps } from './types';

/**
 * 지도 본체. 지도 인스턴스는 한 번만 만들고 핀만 갈아 끼운다.
 * 모드 전환이나 필터 변경으로 지도를 다시 만들지 않으며, 사용자가 보던 중심·확대 수준도 유지한다.
 *
 * 핀 군집화는 features/map/clustering 으로 분리해 두었다(향후 교체 대비).
 */

/** 처음 보여 주는 전국 지도. '전국 보기' 버튼이 이 값으로 되돌린다 */
const NATIONWIDE = { lat: 36.5, lng: 127.8, level: 13 } as const;
/** 확대 한계. 카카오는 숫자가 작을수록 확대다 */
const MIN_LEVEL = 1;
const MAX_LEVEL = 14;

export default function MapPanel({
  pins, selectedId, selectedLocationIds, onSelect, onSelectLocation, onExploreStart,
  onBoundsChange, viewResetKey, className,
}: MapPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const mapsRef = useRef<KakaoMapsApi | null>(null);
  const layerRef = useRef<PinLayer | null>(null);
  const onSelectRef = useRef(onSelect);
  const onSelectLocationRef = useRef(onSelectLocation);
  const onExploreStartRef = useRef(onExploreStart);
  const onBoundsChangeRef = useRef<MapPanelProps['onBoundsChange']>(undefined);
  // 첫 핀 묶음에서만 전체가 보이도록 맞춘다. 이후 필터가 바뀌어도 사용자가 보던 영역을 지킨다.
  const fittedRef = useRef(false);
  const [readyVersion, setReadyVersion] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const appKey = import.meta.env.VITE_KAKAO_MAP_KEY?.trim();
  onSelectRef.current = onSelect;
  onSelectLocationRef.current = onSelectLocation;
  onExploreStartRef.current = onExploreStart;
  onBoundsChangeRef.current = onBoundsChange;

  useEffect(() => {
    if (!appKey) {
      setError('VITE_KAKAO_MAP_KEY가 설정되지 않았습니다.');
      return;
    }

    let disposed = false;
    loadKakaoMaps(appKey)
      .then((maps) => {
        if (disposed || !containerRef.current) return;
        mapsRef.current = maps;
        mapRef.current = new maps.Map(containerRef.current, {
          center: new maps.LatLng(NATIONWIDE.lat, NATIONWIDE.lng),
          level: NATIONWIDE.level,
        });
        layerRef.current = createPinLayer({
          maps,
          map: mapRef.current,
          onSelect: (housingId) => onSelectRef.current?.(housingId),
          onSelectLocation: (housingIds) => onSelectLocationRef.current?.(housingIds),
          onExplore: () => onExploreStartRef.current?.(),
        });
        setReadyVersion((version) => version + 1);
      })
      .catch((cause: unknown) => {
        if (!disposed) setError(cause instanceof Error ? cause.message : '지도를 초기화하지 못했습니다.');
      });

    return () => {
      disposed = true;
      layerRef.current?.destroy();
      layerRef.current = null;
      mapRef.current = null;
      mapsRef.current = null;
    };
  }, [appKey]);

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsRef.current;
    if (!map || !maps || readyVersion === 0) return;

    const notifyBounds = () => {
      const callback = onBoundsChangeRef.current;
      if (!callback) return;
      const bounds = map.getBounds();
      const sw = bounds.getSouthWest();
      const ne = bounds.getNorthEast();
      callback({ swLat: sw.getLat(), swLng: sw.getLng(), neLat: ne.getLat(), neLng: ne.getLng() });
    };

    maps.event.addListener(map, 'idle', notifyBounds);
    return () => maps.event.removeListener(map, 'idle', notifyBounds);
  }, [readyVersion]);

  // 핀 교체. 지도 중심·확대 수준은 건드리지 않는다.
  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsRef.current;
    const layer = layerRef.current;
    if (!map || !maps || !layer || readyVersion === 0) return;

    layer.render(pins);
    layer.highlight(selectedId ?? null, selectedLocationIds);

    // 처음 결과가 들어왔을 때만 전체가 보이게 맞춘다.
    // 필터를 바꿀 때마다 맞추면 특정 지역을 보던 사용자가 매번 전국으로 튕긴다.
    if (!fittedRef.current && pins.length > 0) {
      fittedRef.current = true;
      if (pins.length === 1) {
        map.setCenter(new maps.LatLng(pins[0].lat, pins[0].lng));
        map.setLevel(5);
      } else {
        const bounds = new maps.LatLngBounds();
        pins.forEach((pin) => bounds.extend(new maps.LatLng(pin.lat, pin.lng)));
        map.setBounds(bounds, 64, 64, 64, 64);
      }
    }
    // selectedId 는 아래 effect 가 따로 본다. 여기서 보면 핀을 고를 때마다 전부 다시 그린다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pins, readyVersion]);

  // 선택 강조만 갱신한다. 마커를 다시 만들지 않는다.
  // 주택 선택(selectedId)과 위치 목록(selectedLocationIds)은 따로 움직이므로 둘 다 본다.
  useEffect(() => {
    layerRef.current?.highlight(selectedId ?? null, selectedLocationIds);
  }, [selectedId, selectedLocationIds, readyVersion]);

  /**
   * 지도 컨트롤. 카카오 기본 ZoomControl 은 '전국 보기'가 없고 스타일도 ZIPICK UI 와 따로 놀아,
   * 같은 일을 하는 작은 버튼 세 개를 직접 둔다. 레벨 변경은 SDK 에 그대로 맡긴다.
   */
  const stepZoom = (delta: number) => {
    const map = mapRef.current;
    if (!map) return;
    const next = Math.min(MAX_LEVEL, Math.max(MIN_LEVEL, map.getLevel() + delta));
    map.setLevel(next);
  };

  const resetToNationwide = () => {
    const map = mapRef.current;
    const maps = mapsRef.current;
    if (!map || !maps) return;
    map.setCenter(new maps.LatLng(NATIONWIDE.lat, NATIONWIDE.lng));
    map.setLevel(NATIONWIDE.level);
    // 전국으로 돌아가는 건 탐색을 처음부터 다시 하겠다는 뜻이다.
    layerRef.current?.resetStage();
    onExploreStartRef.current?.();
  };

  // 모드 전환처럼 탐색 맥락이 통째로 바뀌면 들어가 있던 탐색 단계도 푼다.
  useEffect(() => {
    layerRef.current?.resetStage();
  }, [viewResetKey, readyVersion]);

  return (
    <div className={`map-shell ${className ?? ''}`}>
      <div ref={containerRef} className="map-canvas" aria-label="공급주택 지도" />
      {!error && readyVersion === 0 && <div className="map-message">지도를 불러오는 중…</div>}
      {error && (
        <div className="map-message map-message--error">
          <strong>지도를 표시할 수 없습니다.</strong>
          <span>{error}</span>
          {!appKey && <code>zipick-frontend/.env의 VITE_KAKAO_MAP_KEY</code>}
        </div>
      )}
      {!error && readyVersion > 0 && pins.length === 0 && (
        <div className="map-message">조건에 맞는 공급주택이 없습니다.</div>
      )}
      {!error && readyVersion > 0 && <span className="map-count">주택 {pins.length}곳</span>}
      {!error && readyVersion > 0 && (
        <div className="map-controls" role="group" aria-label="지도 조작">
          <button type="button" aria-label="확대" title="확대" onClick={() => stepZoom(-1)}>
            +
          </button>
          <button type="button" aria-label="축소" title="축소" onClick={() => stepZoom(1)}>
            −
          </button>
          <button type="button" aria-label="전국 보기" title="전국 보기" onClick={resetToNationwide}>
            ⌂
          </button>
        </div>
      )}
    </div>
  );
}
