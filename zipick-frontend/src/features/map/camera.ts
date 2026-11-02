import type { KakaoMap, KakaoMapsApi } from './kakaoMaps';
import { SINGLE_PIN_LEVEL } from './regionCluster';
import type { MapPin } from './types';

/** Only the overview camera uses this validation; source data and counts stay intact. */
export function cameraPins(pins: MapPin[]): MapPin[] {
  return pins.filter(({ lat, lng }) => Number.isFinite(lat) && Number.isFinite(lng)
    && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180);
}

/** Use the same overview for initial entry, mode changes, and the overview button. */
export function fitOverview(map: KakaoMap, maps: KakaoMapsApi, pins: MapPin[]): number {
  if (pins.length === 0) return 0;
  const bounds = new maps.LatLngBounds();
  pins.forEach((pin) => bounds.extend(new maps.LatLng(pin.lat, pin.lng)));
  const sw = bounds.getSouthWest();
  const ne = bounds.getNorthEast();
  if (Math.abs(ne.getLat() - sw.getLat()) < 1e-6 && Math.abs(ne.getLng() - sw.getLng()) < 1e-6) {
    map.setLevel(SINGLE_PIN_LEVEL);
    map.setCenter(new maps.LatLng(pins[0].lat, pins[0].lng));
  } else {
    map.setBounds(bounds, 64, 64, 64, 64);
  }
  // Max level 13 may prevent all points fitting on small viewports. Never
  // silently relax the zoom limit or alter coordinates to hide that limitation.
  const visible = map.getBounds();
  const lower = visible.getSouthWest();
  const upper = visible.getNorthEast();
  return pins.filter(({ lat, lng }) => lat < lower.getLat() || lat > upper.getLat()
    || lng < lower.getLng() || lng > upper.getLng()).length;
}
