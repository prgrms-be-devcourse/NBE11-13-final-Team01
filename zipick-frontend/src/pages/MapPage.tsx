import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { MapPanel, toMapPins } from '../features/map';
import { useRecommendationList } from '../hooks/useRecommendationList';

/** 추천 결과를 지도로 보는 화면. 지도 본체는 features/map/MapPanel이 담당한다. */
export default function MapPage() {
  const nav = useNavigate();
  const { data, isLoading, search } = useRecommendationList();
  const pins = useMemo(() => toMapPins(data?.houses ?? []), [data]);
  const [selected, setSelected] = useState<number | null>(null);

  return (
    <div className="content">
      <div className="content__head">
        <h1>지도로 보기</h1>
        <p className="muted">
          추천 {data?.total ?? 0}곳 중 좌표가 있는 {pins.length}곳 ·{' '}
          <Link to={`/recommendations${search ? `?${search}` : ''}`}>목록으로</Link>
        </p>
      </div>
      {isLoading && <p className="muted">추천을 계산하는 중…</p>}
      <MapPanel
        pins={pins}
        selectedId={selected}
        onSelect={(id) => {
          setSelected(id);
          nav(`/houses/${id}`);
        }}
        className="map-panel"
      />
    </div>
  );
}
