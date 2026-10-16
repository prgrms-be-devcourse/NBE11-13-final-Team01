import { useEffect, useRef, useState } from 'react';
import { loadKakaoMaps } from './kakaoMaps';
import type { KakaoCustomOverlay, KakaoLatLng, KakaoMap, KakaoMapsApi } from './kakaoMaps';
import type { MapPanelProps } from './types';

interface PinOverlay {
  overlay: KakaoCustomOverlay;
  element: HTMLButtonElement;
  position: KakaoLatLng;
}

export default function MapPanel({ pins, selectedId, onSelect, onBoundsChange, className }: MapPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const mapsRef = useRef<KakaoMapsApi | null>(null);
  const overlaysRef = useRef<Map<number, PinOverlay>>(new Map());
  const onSelectRef = useRef(onSelect);
  const onBoundsChangeRef = useRef<MapPanelProps['onBoundsChange']>(undefined);
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
        setReadyVersion((version) => version + 1);
      })
      .catch((cause: unknown) => {
        if (!disposed) setError(cause instanceof Error ? cause.message : '지도를 초기화하지 못했습니다.');
      });

    return () => {
      disposed = true;
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

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsRef.current;
    if (!map || !maps || readyVersion === 0) return;

    overlaysRef.current.forEach(({ overlay }) => overlay.setMap(null));
    overlaysRef.current.clear();

    const bounds = new maps.LatLngBounds();
    for (const pin of pins) {
      const position = new maps.LatLng(pin.lat, pin.lng);
      const element = document.createElement('button');
      element.type = 'button';
      element.className = `map-marker${selectedId === pin.housingId ? ' map-marker--selected' : ''}`;
      const label = document.createElement('span');
      label.textContent = '집';
      element.appendChild(label);
      element.title = `${pin.title} · ${pin.address}`;
      element.setAttribute('aria-label', `${pin.title} 선택`);
      element.addEventListener('click', () => onSelectRef.current?.(pin.housingId));

      const overlay = new maps.CustomOverlay({
        position,
        content: element,
        clickable: true,
        zIndex: selectedId === pin.housingId ? 10 : 1,
      });
      overlay.setMap(map);
      overlaysRef.current.set(pin.housingId, { overlay, element, position });
      bounds.extend(position);
    }

    if (pins.length === 1) {
      map.setCenter(new maps.LatLng(pins[0].lat, pins[0].lng));
      map.setLevel(5);
    } else if (pins.length > 1) {
      map.setBounds(bounds, 64, 64, 64, 64);
    }

    return () => {
      overlaysRef.current.forEach(({ overlay }) => overlay.setMap(null));
      overlaysRef.current.clear();
    };
  }, [pins, readyVersion]);

  useEffect(() => {
    overlaysRef.current.forEach(({ overlay, element, position }, houseId) => {
      const selected = houseId === selectedId;
      element.classList.toggle('map-marker--selected', selected);
      overlay.setZIndex(selected ? 10 : 1);
      if (selected) mapRef.current?.panTo(position);
    });
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
        <div className="map-message">좌표가 등록된 공급주택이 없습니다.</div>
      )}
      {!error && readyVersion > 0 && <span className="map-count">핀 {pins.length}개</span>}
    </div>
  );
}
