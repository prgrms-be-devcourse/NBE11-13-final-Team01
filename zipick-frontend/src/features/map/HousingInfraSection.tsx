import { useQuery } from '@tanstack/react-query';
import { getHousingInfra } from '../../api/housingInfra';

/** 1km 미만은 m, 이상은 km로 보여 준다. */
function formatDistance(meters: number) {
  return meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`;
}

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
    <section>
      <h3>주변 생활 인프라</h3>

      {isLoading && <p className="muted">주변 인프라 정보를 불러오는 중…</p>}
      {isError && <p className="muted">주변 인프라 정보를 불러오지 못했습니다.</p>}

      {data && categories.length > 0 && (
        <>
          <dl className="map-detail__facts map-detail__facts--base">
            {categories.map((category) => (
              <div key={category.code}>
                <dt>{category.label}</dt>
                <dd>
                  {category.nearestName} · {formatDistance(category.nearestDistanceMeter)} · 주변{' '}
                  {category.totalCount}곳
                </dd>
              </div>
            ))}
          </dl>
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
