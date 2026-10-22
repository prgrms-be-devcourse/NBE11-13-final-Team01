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
export default function MapPanel({ pins, selectedId, onSelect, onBoundsChange, className }: MapPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const mapsRef = useRef<KakaoMapsApi | null>(null);
  const layerRef = useRef<PinLayer | null>(null);
  const onSelectRef = useRef(onSelect);
  const onBoundsChangeRef = useRef<MapPanelProps['onBoundsChange']>(undefined);
  // 첫 핀 묶음에서만 전체가 보이도록 맞춘다. 이후 필터가 바뀌어도 사용자가 보던 영역을 지킨다.
  const fittedRef = useRef(false);
  const [readyVersion, setReadyVersion] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const appKey = import.meta.env.VITE_KAKAO_MAP_KEY?.trim();
  onSelectRef.current = onSelect;
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
          center: new maps.LatLng(36.5, 127.8),
          level: 13,
        });
        layerRef.current = createPinLayer({
          maps,
          map: mapRef.current,
          onSelect: (housingId) => onSelectRef.current?.(housingId),
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
    layer.highlight(selectedId ?? null);

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
  useEffect(() => {
    layerRef.current?.highlight(selectedId ?? null);
  }, [selectedId, readyVersion]);

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
    </div>
  );
}
