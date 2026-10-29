import { useEffect, useRef, useState } from 'react';
import { createPinLayer, type PinLayer } from './clustering';
import { loadKakaoMaps } from './kakaoMaps';
import type { KakaoMap, KakaoMapsApi } from './kakaoMaps';
import type { MapPanelProps } from './types';
import { cameraPins, fitOverview } from './camera';

/**
 * 지도 본체. 지도 인스턴스는 한 번만 만들고 핀만 갈아 끼운다.
 * 모드 진입 시 결과 범위를 한 번 맞추고, 이후 재조회나 필터 변경은 사용자의 시점을 유지한다.
 *
 * 핀 군집화는 features/map/clustering 으로 분리해 두었다(향후 교체 대비).
 */

/** 데이터 준비 전의 기본 시점. 결과 개요와 전국 보기는 실제 좌표로 맞춘다. */
const NATIONWIDE = { lat: 36.5, lng: 127.8, level: 13 } as const;
/** 확대 한계. 카카오는 숫자가 작을수록 확대다 */
const MIN_LEVEL = 1;
const MAX_LEVEL = 13;

export default function MapPanel({
  pins, selectedId, selectedLocationIds, onSelect, onSelectLocation, onExploreStart,
  onBoundsChange, viewResetKey, viewReady = true, emptyMessage, className,
}: MapPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const mapsRef = useRef<KakaoMapsApi | null>(null);
  const layerRef = useRef<PinLayer | null>(null);
  const onSelectRef = useRef(onSelect);
  const onSelectLocationRef = useRef(onSelectLocation);
  const onExploreStartRef = useRef(onExploreStart);
  const onBoundsChangeRef = useRef<MapPanelProps['onBoundsChange']>(undefined);
  const prepareLayoutRef = useRef<(() => boolean) | null>(null);
  const overviewRef = useRef({ key: viewResetKey, action: 0, version: 0, fitted: false });
  const [overviewAction, setOverviewAction] = useState(0);
  const [overviewLimited, setOverviewLimited] = useState(false);
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
        // Apply the same limit to wheel/touch zoom and SDK-driven bounds changes.
        mapRef.current.setMaxLevel(MAX_LEVEL);
        const theme = getComputedStyle(containerRef.current);
        layerRef.current = createPinLayer({
          colors: {
            primary: theme.getPropertyValue('--zipick-blue').trim() || '#2059d6',
            selected: theme.getPropertyValue('--zipick-blue-strong').trim() || '#1646b3',
          },
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

  // Resizing the canvas must not recreate the SDK instance or refit the data.
  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsRef.current;
    const container = containerRef.current;
    if (!map || !maps || !container || readyVersion === 0) return;
    let width = container.clientWidth;
    let height = container.clientHeight;
    let center = map.getCenter();
    let frame: number | null = null;
    let zooming = false;
    let layingOut = false;
    let disposed = false;
    let viewChanged = false;

    // The SDK derives getCenter() from its pixel origin and the current element
    // size. Cache view changes before a resize, not after the DOM has resized.
    const rememberCenter = () => {
      if (!layingOut && width === container.clientWidth && height === container.clientHeight) {
        center = map.getCenter();
        viewChanged = true;
      }
    };
    const prepareLayout = () => {
      if (disposed || zooming) return false;
      const nextWidth = container.clientWidth;
      const nextHeight = container.clientHeight;
      if (!nextWidth || !nextHeight) return false;
      if (nextWidth === width && nextHeight === height) return true;
      const retained = center;
      layingOut = true;
      viewChanged = false;
      width = nextWidth;
      height = nextHeight;
      try {
        map.relayout();
        map.setCenter(retained);
      } finally {
        layingOut = false;
      }
      return true;
    };
    prepareLayoutRef.current = prepareLayout;
    const scheduleLayout = () => {
      if (disposed || zooming || frame != null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        prepareLayout();
      });
    };
    const onZoomStart = () => { zooming = true; };
    const onIdle = () => {
      zooming = false;
      // Camera setters can emit center_changed before their final pixel origin
      // settles. Capture that final view, but never adopt our own resize rounding.
      if (viewChanged && !layingOut && width === container.clientWidth && height === container.clientHeight) {
        center = map.getCenter();
        viewChanged = false;
      }
      scheduleLayout();
    };
    maps.event.addListener(map, 'center_changed', rememberCenter);
    maps.event.addListener(map, 'zoom_start', onZoomStart);
    maps.event.addListener(map, 'idle', onIdle);
    const observer = new ResizeObserver(scheduleLayout);
    observer.observe(container);
    return () => {
      disposed = true;
      prepareLayoutRef.current = null;
      observer.disconnect();
      if (frame != null) cancelAnimationFrame(frame);
      maps.event.removeListener(map, 'center_changed', rememberCenter);
      maps.event.removeListener(map, 'zoom_start', onZoomStart);
      maps.event.removeListener(map, 'idle', onIdle);
    };
  }, [readyVersion]);

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

    // selectedId 는 아래 effect 가 따로 본다. 여기서 보면 핀을 고를 때마다 전부 다시 그린다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pins, readyVersion]);

  // Consume one overview request per mode entry (including empty results), not
  // per fetch/render. Idle retries only a pending request during an SDK zoom.
  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsRef.current;
    const layer = layerRef.current;
    const previous = overviewRef.current;
    if (previous.key !== viewResetKey || previous.action !== overviewAction || previous.version !== readyVersion) {
      overviewRef.current = { key: viewResetKey, action: overviewAction, version: readyVersion, fitted: false };
    }
    const request = overviewRef.current;
    if (!map || !maps || !layer || !viewReady || request.fitted) return;
    let frame: number | null = null;
    const schedule = () => {
      if (request.fitted || frame != null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        if (request !== overviewRef.current || request.fitted || !prepareLayoutRef.current?.()) return;
        request.fitted = true;
        const valid = cameraPins(pins);
        setOverviewLimited(fitOverview(map, maps, valid) > 0);
        // Fit first, then reset the stage using the actual SDK level. The layer
        // also reconciles on idle if the SDK finishes a camera change later.
        layer.resetStage();
      });
    };
    maps.event.addListener(map, 'idle', schedule);
    schedule();
    return () => {
      if (frame != null) cancelAnimationFrame(frame);
      maps.event.removeListener(map, 'idle', schedule);
    };
  }, [pins, viewReady, viewResetKey, overviewAction, readyVersion]);

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
    // Closing a detail can resize the map. Fit after React commits that layout.
    onExploreStartRef.current?.();
    setOverviewAction((value) => value + 1);
  };

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
      {!error && readyVersion > 0 && viewReady && pins.length === 0 && (
        <div className="map-message">{emptyMessage ?? '조건에 맞는 공급주택이 없습니다.'}</div>
      )}
      {!error && readyVersion > 0 && <span className="map-count">주택 {pins.length}곳{overviewLimited && ' · 일부 위치는 지도를 이동해 확인하세요'}</span>}
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
