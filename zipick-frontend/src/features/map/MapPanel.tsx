import type { MapPanelProps } from './types';

/**
 * 지도 자리. 지도 담당자가 이 컴포넌트 본문을 카카오맵 구현으로 바꾸면 된다.
 * - props(MapPanelProps)와 export 이름은 유지한다. 페이지 쪽은 손댈 필요 없다.
 * - 카카오 JS 키는 .env의 VITE_KAKAO_MAP_KEY로 읽는다 (.env.example 참고).
 * - 핀 데이터는 toMapPins()가 추천 응답에서 만들어 준다.
 */
export default function MapPanel({ pins, selectedId, onSelect, className }: MapPanelProps) {
  return (
    <div className={`placeholder ${className ?? ''}`}>
      <strong>지도 영역</strong>
      <p>핀 {pins.length}개가 준비돼 있어요. 지도 구현이 붙으면 여기에 표시돼요.</p>
      <ul className="placeholder__list">
        {pins.map((p) => (
          <li key={p.houseId}>
            <button type="button" className={`chip ${selectedId === p.houseId ? 'chip--on' : ''}`} onClick={() => onSelect?.(p.houseId)}>
              {p.rank}. {p.title} · {p.score}점 ({p.lat.toFixed(4)}, {p.lng.toFixed(4)}){p.approximate ? ' · 근사' : ''}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
