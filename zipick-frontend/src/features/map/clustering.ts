import type {
  KakaoCustomOverlay,
  KakaoLatLngBounds,
  KakaoMap,
  KakaoMapsApi,
  KakaoMarker,
  KakaoMarkerImage,
} from './kakaoMaps';
import { groupByLocation, type LocationGroup } from './locationGroup';
import {
  SINGLE_PIN_LEVEL, effectiveUnit, layoutPins, nextStage, releasedByZoom, shortProvince,
  type RegionCluster, type StageOverride,
} from './regionCluster';
import type { MapPin } from './types';

/**
 * 지도 핀 **행정구역별 군집화**.
 *
 * 축소하면 시·도, 중간 확대에서는 시·군·구, 더 확대하면 개별 주택 핀을 보여 준다.
 * 화면 좌표가 가까운 핀을 묶던 SDK MarkerClusterer 와 달리 **행정구역 자체가 탐색 단위**이고,
 * 군집에 지역명과 고유 주택 수가 함께 뜬다.
 *
 * 역할 분담
 *  - regionCluster.ts  무엇을 어떻게 묶을지 (SDK 무의존 순수 함수)
 *  - 이 파일            카카오 오버레이·마커로 그리고 치우는 일
 *
 * MapPanel 은 이 파일이 돌려주는 {@link PinLayer} 계약(render/highlight/destroy)만 안다.
 * 확대 수준은 여기서 지도의 zoom_changed 를 직접 듣는다 — 군집 단위를 고르는 데만 쓰므로
 * MapPanel·MapPage 로 올릴 이유가 없다(지도를 움직일 때 서버를 다시 부르지 않는다).
 *
 * 설계 배경은 docs/map-clustering.md 참고.
 */

/** 군집 원 크기 구간. 주택 수가 이 값을 넘을 때마다 다음 단계 스타일을 쓴다 */
const SIZE_THRESHOLDS = [10, 50, 200];

const PIN_SVG = (fill: string, stroke: string) =>
  `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="36" viewBox="0 0 28 36">` +
      `<path d="M14 1C7.4 1 2 6.3 2 12.9 2 21.4 14 35 14 35s12-13.6 12-22.1C26 6.3 20.6 1 14 1z" ` +
      `fill="${fill}" stroke="${stroke}" stroke-width="2"/>` +
      `<circle cx="14" cy="13" r="4.5" fill="#ffffff"/></svg>`,
  )}`;

/**
 * 같은 위치에 주택이 여러 곳일 때 쓰는 핀. **일반 핀과 같은 모양**에 우측 상단 숫자 배지만 더한다.
 * 행정구역 군집(둥근 알약)과 달리 지도 핀으로 보이게 하려는 것이다.
 *
 * 핀 본체는 PIN_SVG 와 같은 path 라서 일반 핀 옆에 나란히 놓여도 어색하지 않다.
 * 배지 자리를 만드느라 위쪽이 넓어지므로 기준점(offset)을 핀 끝으로 다시 잡아 준다.
 */
const BADGE_PIN = (fill: string, stroke: string, badgeFill: string, badgeText: string, badgeStroke: string, count: number) => {
  const label = count > 99 ? '99+' : String(count);
  const fontSize = label.length >= 3 ? 9 : label.length === 2 ? 11 : 12;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="42" height="46" viewBox="0 0 42 46">` +
      `<g transform="translate(0,10)">` +
      `<path d="M14 1C7.4 1 2 6.3 2 12.9 2 21.4 14 35 14 35s12-13.6 12-22.1C26 6.3 20.6 1 14 1z" ` +
      `fill="${fill}" stroke="${stroke}" stroke-width="2"/>` +
      `<circle cx="14" cy="13" r="4.5" fill="#ffffff"/></g>` +
      `<circle cx="30" cy="12" r="10.5" fill="${badgeFill}" stroke="${badgeStroke}" stroke-width="2"/>` +
      `<text x="30" y="12" text-anchor="middle" dominant-baseline="central" ` +
      `font-family="-apple-system,BlinkMacSystemFont,'Noto Sans KR',sans-serif" ` +
      `font-size="${fontSize}" font-weight="700" fill="${badgeText}">${label}</text>` +
      `</svg>`,
  )}`;
};

export interface PinLayer {
  /** 핀 목록을 통째로 교체한다. 이전 마커·오버레이는 지도에서 모두 떼어 낸다. */
  render(pins: MapPin[]): void;
  /**
   * 선택 상태를 갱신한다. 지도 중심은 건드리지 않는다.
   *
   * @param housingId          상세를 보고 있는 주택
   * @param locationHousingIds 우측에 "이 위치의 주택 N개" 목록이 열려 있는 위치의 주택 id 들.
   *                           상세로 들어가도 같은 위치 강조를 유지하려고 따로 받는다
   *                           (주택 선택과 위치 선택은 생명주기가 다르다).
   */
  highlight(housingId: number | null, locationHousingIds?: readonly number[] | null): void;
  /**
   * 군집을 눌러 들어간 탐색 단계를 처음(확대 수준이 정하는 기본 단계)으로 되돌린다.
   * 지도는 움직이지 않는다. '전국 보기'나 모드 전환처럼 **탐색 맥락이 완전히 바뀔 때** 바깥에서 부른다.
   */
  resetStage(): void;
  /** 지도에서 모두 떼어 내고 자원을 정리한다. */
  destroy(): void;
}

interface Options {
  colors?: { primary: string; selected: string };
  maps: KakaoMapsApi;
  map: KakaoMap;
  onSelect: (housingId: number) => void;
  /** 같은 좌표에 주택이 둘 이상일 때. 없으면 첫 주택을 그냥 선택한다 */
  onSelectLocation?: (housingIds: number[]) => void;
  /** 행정구역 군집을 눌러 새 탐색을 시작했을 때. 바깥에서 이전 선택 맥락을 정리하라고 알린다 */
  onExplore?: () => void;
}

/** 지도에 올라가 있는 군집 오버레이 하나. 치울 때 필요한 것을 함께 들고 있는다 */
interface DrawnCluster {
  cluster: RegionCluster;
  overlay: KakaoCustomOverlay;
  element: HTMLElement;
  onClick: () => void;
}

/** 같은 좌표에 주택이 둘 이상이라 숫자 배지를 단 위치 마커 */
interface DrawnLocation {
  group: LocationGroup;
  marker: KakaoMarker;
}

/**
 * 핀 + 군집 레이어를 만든다. 지도 인스턴스는 바깥(MapPanel)이 들고 있고,
 * 여기서는 마커·오버레이만 올렸다 내린다. 모드나 필터가 바뀌어도 지도는 다시 만들지 않는다.
 */
export function createPinLayer({ maps, map, onSelect, onSelectLocation, onExplore, colors = { primary: '#2059d6', selected: '#1646b3' } }: Options): PinLayer {
  const normalImage = new maps.MarkerImage(PIN_SVG(colors.primary, '#ffffff'), new maps.Size(28, 36), {
    offset: new maps.Point(14, 35),
  });
  const selectedImage = new maps.MarkerImage(PIN_SVG(colors.selected, '#ffffff'), new maps.Size(34, 44), {
    offset: new maps.Point(17, 43),
  });

  /**
   * 배지 핀 이미지. 같은 (주택 수, 선택 여부) 조합은 다시 만들지 않는다 —
   * 줌을 왕복하면 draw() 가 반복되는데 매번 SVG data URI 를 새로 만들 이유가 없다.
   */
  const badgeImages = new Map<string, KakaoMarkerImage>();
  function badgeImage(count: number, selected: boolean): KakaoMarkerImage {
    const key = `${count}:${selected}`;
    const cached = badgeImages.get(key);
    if (cached) return cached;
    const src = selected
      // Selection uses the stronger brand blue and an inverted count badge.
      ? BADGE_PIN(colors.selected, '#ffffff', '#ffffff', colors.selected, colors.selected, count)
      // A white outline separates the normal blue pin from the map.
      : BADGE_PIN(colors.primary, '#ffffff', colors.selected, '#ffffff', '#ffffff', count);
    const image = new maps.MarkerImage(src, new maps.Size(42, 46), { offset: new maps.Point(14, 45) });
    badgeImages.set(key, image);
    return image;
  }

  let pins: MapPin[] = [];
  /** 군집을 눌러 들어간 탐색 단계. 확대 수준과 별개로 **화면 전체**의 표시 단계를 정한다 */
  let stage: StageOverride | null = null;
  /** 지금 그려야 할 모습을 요약한 값. 이게 바뀔 때만 다시 그린다 */
  let signature = '';
  let selectedId: number | null = null;
  /** 목록이 열려 있는 위치. 주택 상세로 들어가도 유지되고, 목록을 닫을 때만 비워진다 */
  let selectedLocation: Set<number> = new Set();
  let disposed = false;

  /** 지금 지도에 올라가 있는 것. draw() 마다 통째로 갈아 끼운다 */
  const markers = new Map<number, KakaoMarker>();
  const clusters: DrawnCluster[] = [];
  const locations: DrawnLocation[] = [];
  /** draw() 가 등록한 마커 리스너. 다음 draw() 에서 반드시 해제한다(줌마다 쌓이면 누수다) */
  let drawnListeners: { target: object; event: string; handler: (...args: never[]) => void }[] = [];

  /**
   * 지금 그려야 할 모습 = 화면 전체에 적용되는 단계 하나. 같으면 다시 그리지 않는다.
   * 단계가 전역이 되면서 "어느 지역을 눌렀는지"는 그림에 영향을 주지 않는다.
   */
  function currentSignature() {
    return effectiveUnit(map.getLevel(), stage);
  }

  // 레이어가 사는 동안 유지되는 리스너. destroy() 에서만 해제한다.
  const zoomHandler = (() => {
    if (disposed) return;
    // 들어간 때보다 축소했으면 푼다(상위 단계 탐색으로 돌아간 것으로 본다).
    if (releasedByZoom(stage, map.getLevel())) stage = null;
    const next = currentSignature();
    if (next === signature) return;   // 같은 모습이면 다시 그릴 이유가 없다
    signature = next;
    draw();
  }) as unknown as (...args: never[]) => void;
  maps.event.addListener(map, 'zoom_changed', zoomHandler);
  // Some camera operations finish after zoom_changed. Reconcile against the
  // SDK's final level without discarding a user's drill-down stage.
  maps.event.addListener(map, 'idle', zoomHandler);

  /** 지도에 올라간 마커·오버레이와 그 리스너를 전부 떼어 낸다 */
  function clearDrawn() {
    markers.forEach((marker) => marker.setMap(null));
    markers.clear();

    for (const drawn of locations) drawn.marker.setMap(null);
    locations.length = 0;

    for (const drawn of clusters) {
      drawn.overlay.setMap(null);
      // DOM 리스너는 maps.event 가 모른다. 여기서 직접 떼어야 한다.
      drawn.element.removeEventListener('click', drawn.onClick);
    }
    clusters.length = 0;

    drawnListeners.forEach(({ target, event, handler }) => maps.event.removeListener(target, event, handler));
    drawnListeners = [];
  }

  function listenOnce(target: object, event: string, handler: (...args: never[]) => void) {
    maps.event.addListener(target, event, handler);
    drawnListeners.push({ target, event, handler });
  }

  /** 현재 확대 수준에 맞춰 군집과 개별 핀을 다시 그린다 */
  function draw() {
    clearDrawn();
    const layout = layoutPins(pins, map.getLevel(), stage);

    // 개별 핀은 먼저 **좌표로 묶는다.** 같은 좌표에 여러 주택이 있으면 마커가 완전히 겹쳐
    // 화면에서 하나처럼 보이고, 클릭도 맨 위 하나만 닿는다. 묶어서 숫자로 보여 준다.
    for (const group of groupByLocation(layout.loose)) {
      if (group.pins.length === 1) {
        const pin = group.pins[0];
        const selected = pin.housingId === selectedId;
        const marker = new maps.Marker({
          position: new maps.LatLng(pin.lat, pin.lng),
          title: `${pin.title} · ${pin.address}`,
          image: selected ? selectedImage : normalImage,
          zIndex: selected ? 10 : 1,
          clickable: true,
        });
        listenOnce(marker, 'click', (() => onSelect(pin.housingId)) as unknown as (...args: never[]) => void);
        marker.setMap(map);
        markers.set(pin.housingId, marker);
        continue;
      }

      const selected = locationHasSelected(group);
      const marker = new maps.Marker({
        position: new maps.LatLng(group.lat, group.lng),
        title: group.pins.map((pin) => `${pin.title} · ${pin.address}`).join(' / '),
        image: badgeImage(group.pins.length, selected),
        zIndex: selected ? 10 : 2,
        clickable: true,
      });
      const ids = group.pins.map((pin) => pin.housingId);
      listenOnce(marker, 'click', (() => {
        // 목록을 받을 쪽이 없으면 예전처럼 첫 주택을 연다(기능이 사라지지 않게).
        if (onSelectLocation) onSelectLocation(ids);
        else onSelect(ids[0]);
      }) as unknown as (...args: never[]) => void);
      marker.setMap(map);
      locations.push({ group, marker });
    }

    for (const cluster of layout.clusters) {
      const element = clusterElement(cluster, containsSelected(cluster));
      const onClick = () => focusCluster(cluster);
      element.addEventListener('click', onClick);
      const overlay = new maps.CustomOverlay({
        position: new maps.LatLng(cluster.lat, cluster.lng),
        content: element,
        // 오버레이 위 클릭이 지도로 새지 않게 한다.
        clickable: true,
        zIndex: 5,
      });
      overlay.setMap(map);
      clusters.push({ cluster, overlay, element, onClick });
    }
  }

  function containsSelected(cluster: RegionCluster) {
    return selectedId != null && cluster.pins.some((pin) => pin.housingId === selectedId);
  }

  /**
   * 선택한 주택이 이 위치에 있는지. 중복으로 숨긴 주택(merged)도 본다 —
   * `/map?housing=` 로 직접 들어온 주택이 숨겨진 쪽이어도 위치는 선택 상태로 보여야 한다.
   */
  function locationHasSelected(group: LocationGroup) {
    // ① 이 위치의 목록이 열려 있다 (주택을 아직 고르지 않았어도 강조한다)
    if (selectedLocation.size > 0 && group.pins.some((pin) => selectedLocation.has(pin.housingId))) {
      return true;
    }
    // ② 상세를 보고 있는 주택이 이 위치에 있다. 중복으로 숨긴 주택(merged)도 본다 —
    //    /map?housing= 로 직접 들어온 주택이 숨겨진 쪽이어도 위치는 선택 상태로 보여야 한다.
    if (selectedId == null) return false;
    return group.pins.some((pin) => pin.housingId === selectedId)
      || group.merged.some((pin) => pin.housingId === selectedId);
  }

  /**
   * 군집을 누르면 그 군집에 묶인 주택들의 좌표 범위로 확대·이동하고, **지도를 다음 탐색 단계로 들여보낸다.**
   *
   * 누른 군집은 **어디를 볼지**(fitBounds 대상)만 정한다. **어떤 단계로 볼지**는 화면 전체에 같이 적용된다.
   * 행정구역 경계 polygon 은 쓰지 않는다 — 실제 데이터 범위에 맞추는 편이 추가 데이터 없이 정확하다.
   *
   * ⚠️ fitBounds 로 맞춘 확대 수준을 **절대 건드리지 않는다.** 예전에는 레벨을 강제로 당겨
   * 다음 단계를 띄웠는데, 그러면 범위 보장이 깨져 주택이 화면 밖으로 밀려났다(원주 16곳 → 0곳).
   */
  function focusCluster(cluster: RegionCluster) {
    const bounds = new maps.LatLngBounds();
    cluster.pins.forEach((pin) => bounds.extend(new maps.LatLng(pin.lat, pin.lng)));

    // 주택이 하나거나 좌표가 사실상 같은 지점이면 범위가 한 점이 되어 최대 확대로 튄다.
    if (cluster.pins.length === 1 || isDegenerate(bounds)) {
      map.setCenter(new maps.LatLng(cluster.lat, cluster.lng));
      map.setLevel(SINGLE_PIN_LEVEL);
      enterStage(cluster.unit);
      return;
    }
    map.setBounds(bounds, 48, 48, 48, 48);
    // Kakao 의 setBounds 는 동기로 반영된다(직후 getLevel() 이 최종값과 같은 것을 확인했다).
    enterStage(cluster.unit);
  }

  /**
   * 지도 전체를 다음 단계로 들여보내고 다시 그린다. 지도는 더 움직이지 않는다.
   * 기준 레벨은 **fitBounds 가 끝난 뒤의 값**이라, 여기서 더 축소하면 이 단계가 풀린다.
   */
  function enterStage(unit: 'province' | 'district') {
    stage = { unit: nextStage(unit), level: map.getLevel() };
    signature = currentSignature();
    draw();
    onExplore?.();   // 바깥에서 이전 선택 맥락을 정리하도록
  }

  return {
    render(next) {
      pins = next;
      // 필터·모드가 바뀌면 보고 있던 대상이 달라지므로 탐색 단계도 버린다(기본 확대 규칙으로 돌아간다).
      stage = null;
      signature = currentSignature();
      draw();
    },

    highlight(housingId, locationHousingIds) {
      selectedId = housingId;
      selectedLocation = new Set(locationHousingIds ?? []);
      // 개별 핀은 이미지만 바꾸면 되고, 군집은 선택된 집을 품고 있는지 표시만 갱신한다.
      // 어느 쪽이든 지도는 움직이지 않는다.
      markers.forEach((marker, id) => {
        const selected = id === housingId;
        marker.setImage(selected ? selectedImage : normalImage);
        marker.setZIndex(selected ? 10 : 1);
      });
      for (const drawn of clusters) {
        drawn.element.classList.toggle('map-cluster--has-selected', containsSelected(drawn.cluster));
      }
      for (const drawn of locations) {
        const selected = locationHasSelected(drawn.group);
        drawn.marker.setImage(badgeImage(drawn.group.pins.length, selected));
        drawn.marker.setZIndex(selected ? 10 : 2);
      }
    },

    resetStage() {
      stage = null;
      const next = currentSignature();
      if (next === signature) return;   // 확대 수준이 이미 같은 단계면 다시 그릴 이유가 없다
      signature = next;
      draw();
    },

    destroy() {
      disposed = true;
      clearDrawn();
      maps.event.removeListener(map, 'zoom_changed', zoomHandler);
      maps.event.removeListener(map, 'idle', zoomHandler);
    },
  };
}

/**
 * 군집 오버레이 엘리먼트.
 *
 * 지역명은 외부에서 수집한 데이터라 innerHTML 로 넣지 않는다(textContent 만 쓴다).
 * 키보드로도 누를 수 있도록 button 으로 만든다.
 */
function clusterElement(cluster: RegionCluster, selected: boolean): HTMLElement {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = [
    'map-cluster',
    `map-cluster--${cluster.unit}`,
    `map-cluster--s${sizeStep(cluster.pins.length)}`,
    selected ? 'map-cluster--has-selected' : '',
  ]
    .filter(Boolean)
    .join(' ');
  button.setAttribute('aria-label', `${cluster.label} ${cluster.pins.length}곳, 눌러서 확대`);

  const label = document.createElement('span');
  label.className = 'map-cluster__label';
  label.textContent = cluster.unit === 'province' ? shortProvince(cluster.label) : cluster.label;

  const count = document.createElement('span');
  count.className = 'map-cluster__count';
  // 군집 숫자는 **고유 주택 수**다. 핀 하나가 주택 하나이므로 묶인 핀 수가 곧 주택 수다.
  count.textContent = String(cluster.pins.length);

  button.append(label, count);
  return button;
}

function sizeStep(count: number): number {
  let step = 0;
  for (const threshold of SIZE_THRESHOLDS) {
    if (count < threshold) break;
    step += 1;
  }
  return step;
}

/** 범위가 사실상 한 점인지. 같은 단지에 공고가 여러 건이면 좌표가 겹친다 */
function isDegenerate(bounds: KakaoLatLngBounds): boolean {
  const sw = bounds.getSouthWest();
  const ne = bounds.getNorthEast();
  return Math.abs(ne.getLat() - sw.getLat()) < 1e-6 && Math.abs(ne.getLng() - sw.getLng()) < 1e-6;
}

/** 선택한 핀을 알 수 없는 마커 이미지 */
export type { KakaoMarkerImage };
