import { useEffect, useRef, useState } from 'react';
import { loadKakaoMaps } from './kakaoMaps';

interface RoadviewPanelProps {
  latitude: number;
  longitude: number;
}

export default function RoadviewPanel({ latitude, longitude }: RoadviewPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [unavailable, setUnavailable] = useState(false);
  const appKey = import.meta.env.VITE_KAKAO_MAP_KEY?.trim();

  useEffect(() => {
    if (!appKey || !containerRef.current) return;
    let disposed = false;
    setUnavailable(false);

    loadKakaoMaps(appKey).then((maps) => {
      if (disposed || !containerRef.current) return;
      const position = new maps.LatLng(latitude, longitude);
      const roadview = new maps.Roadview(containerRef.current);
      const client = new maps.RoadviewClient();
      client.getNearestPanoId(position, 100, (panoId) => {
        if (disposed) return;
        if (panoId == null) {
          setUnavailable(true);
          return;
        }
        roadview.setPanoId(panoId, position);
        roadview.relayout();
      });
    }).catch(() => setUnavailable(true));

    return () => {
      disposed = true;
    };
  }, [appKey, latitude, longitude]);

  return (
    <div className="roadview-wrap">
      <div ref={containerRef} className="roadview" aria-label="선택한 공급주택 주변 로드뷰" />
      {unavailable && <p className="roadview__message">주변 100m 이내의 로드뷰가 없습니다.</p>}
    </div>
  );
}
