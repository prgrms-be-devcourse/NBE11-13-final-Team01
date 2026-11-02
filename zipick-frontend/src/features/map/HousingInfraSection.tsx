import { useQuery } from '@tanstack/react-query';
import { getHousingInfra } from '../../api/housingInfra';

/** 1km 미만은 m, 이상은 km로 보여 준다. */
function formatDistance(meters: number) {
  return meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`;
}

const iconPaths: Record<string, string> = {
  SUBWAY: 'M5 17V6a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v11H5Zm0-5h14M8 17l-2 4m10-4 2 4M8 7h2m4 0h2',
  MART: 'M3 4h2l2 12h12l2-9H6M9 20h.01M18 20h.01',
  CONVENIENCE_STORE: 'M4 10h16v11H4V10Zm-1 0 2-6h14l2 6H3Zm5 11v-7h5v7',
  HOSPITAL: 'M4 21V6h16v15M2 21h20M10 9h4m-2-2v4M9 21v-5h6v5',
  PHARMACY: 'M9 3h6v4H9V3Zm-2 4h10l2 3v11H5V10l2-3Zm5 4v7m-3.5-3.5h7',
  SCHOOL: 'M3 21V9l9-6 9 6v12H3Zm7 0v-7h4v7M7 10h2m6 0h2',
};

/**
 * 주택 상세 패널의 주변 생활 인프라 섹션.
 * 상세 조회와 별도 요청으로 가져와 상세 화면이 먼저 뜨도록 한다.
 */
export default function HousingInfraSection({ housingId }: { housingId: number }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['map-housing-infra', housingId],
    queryFn: () => getHousingInfra(housingId),
    // 서버가 30일 TTL로 캐시하므로 화면에서 자주 다시 부를 이유가 없다.
    staleTime: 30 * 60 * 1000,
  });

  const categories = data?.categories ?? [];

  return (
    <section className="housing-infra">
      <h3>주변 생활 인프라</h3>
      {categories.length > 0 && (
        <p className="housing-infra__description">
          주택 위치에서 반경 {(categories[0].radiusMeter / 1000).toLocaleString('ko-KR')}km 이내의 생활 인프라 정보예요.
        </p>
      )}

      {isLoading && <p className="muted">주변 인프라 정보를 불러오는 중…</p>}
      {isError && <p className="muted">주변 인프라 정보를 불러오지 못했습니다.</p>}

      {data && categories.length > 0 && (
        <>
          <div className="housing-infra__grid">
            {categories.map((category) => (
              <div className="housing-infra__card" key={category.code}>
                <div className="housing-infra__heading">
                  <span className="housing-infra__icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[category.code] ?? iconPaths.CONVENIENCE_STORE} /></svg></span>
                  <strong>{category.label}</strong>
                </div>
                <div className="housing-infra__place">{category.nearestName} <span>· {formatDistance(category.nearestDistanceMeter)}</span></div>
                <small className="housing-infra__count">주변 {category.totalCount}곳</small>
              </div>
            ))}
          </div>
          <p className="muted infra-note">
            카카오맵에 등록된 장소 기준이며, 거리는 실제 도보 경로가 아닌 <strong>직선거리</strong>입니다.
            {data.status === 'STALE' && ' 이전에 수집한 정보를 표시하고 있습니다.'}
          </p>
        </>
      )}

      {data && categories.length === 0 && (
        <p className="muted infra-note">
          {data.status === 'FRESH' && '설정한 반경 안에서 확인된 생활 인프라가 없습니다.'}
          {data.status === 'STALE' && '이전 수집 결과에 표시할 생활 인프라가 없습니다.'}
          {data.status === 'PENDING' && '주변 인프라 정보를 준비 중입니다. 잠시 후 다시 확인해 주세요.'}
          {data.status === 'UNAVAILABLE' && '위치 정보가 확인되지 않아 주변 인프라를 제공할 수 없습니다.'}
        </p>
      )}
    </section>
  );
}
