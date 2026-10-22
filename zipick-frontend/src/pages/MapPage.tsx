import { useEffect, useMemo } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { houseApi } from '../api/house';
import { profileApi } from '../api/profile';
import { getMapHousingDetail } from '../api/mapHousing';
import FilterPanel from '../components/FilterPanel';
import HousingDetail from '../components/HousingDetail';
import ReasonList from '../components/ReasonList';
import RecommendationSidebar from '../components/map/RecommendationSidebar';
import { MapPanel, type MapPin } from '../features/map';
import { useAuth } from '../hooks/useAuth';
import { openGate } from '../hooks/useAuthGate';
import { useHouseFilter } from '../hooks/useHouseFilter';
import { useOnboardingStatus } from '../hooks/useOnboardingStatus';
import { useRecommendationBoard } from '../hooks/useRecommendationBoard';
import type { RecommendedHouse } from '../types';

/**
 * 지도 한 화면에서 전체 공고와 맞춤 추천을 전환해 탐색한다.
 * 모드·선택한 핀·필터는 모두 URL 쿼리에 있어 로그인하러 갔다 와도 같은 화면으로 돌아온다.
 *
 *   /map                         전체 공고
 *   /map?mode=recommended        맞춤 추천 (로그인 + 내 조건 필요)
 *   /map?housing=123             123번 주택 상세 패널 열림
 */

/**
 * 전체 공고 모드의 조회 상한. HouseFilterService 의 MAX_LIMIT 과 같은 값이다.
 * ⚠️ 서버는 이와 별개로 내부에서 호실 10,000건만 읽어 주택으로 묶으므로, 이 값을 올려도
 * 그 위에서는 더 받지 못한다. 잘렸는지는 total 과 받은 개수를 비교해 아래에서 안내한다.
 */
const PIN_LIMIT = 2000;

type MapMode = 'all' | 'recommended';

function toPins(
  houses: { houseId: number; housingName?: string; title: string; address: string; lat?: number; lng?: number }[],
): MapPin[] {
  return houses
    .filter((h) => h.lat != null && h.lng != null)
    .map((h) => ({ housingId: h.houseId, title: h.housingName || h.title, address: h.address, lat: h.lat!, lng: h.lng! }));
}

export default function MapPage() {
  const [params, setParams] = useSearchParams();
  const { filter, setFilter } = useHouseFilter();
  const { user } = useAuth();

  const mode: MapMode = params.get('mode') === 'recommended' ? 'recommended' : 'all';
  const parsed = Number(params.get('housing'));
  const selectedId = Number.isFinite(parsed) && parsed > 0 ? parsed : null;

  const { status: onboarding } = useOnboardingStatus(mode === 'recommended');
  const canRecommend = mode === 'recommended' && !!user && onboarding === 'COMPLETE';

  useEffect(() => {
    if (mode !== 'recommended') return;
    if (!user) openGate('LOGIN');
    else if (onboarding === 'INCOMPLETE') openGate('ONBOARDING');
  }, [mode, user, onboarding]);

  const patch = (next: Record<string, string | null>) => {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === null) merged.delete(key);
      else merged.set(key, value);
    }
    setParams(merged, { replace: true });
  };

  // 전체 공고: 공개 필터 API. 비회원도 쓸 수 있고 공고 예정 + 접수 중을 모두 돌려준다.
  const allQuery = useQuery({
    queryKey: ['houses', filter, PIN_LIMIT],
    queryFn: () => houseApi.list(filter, PIN_LIMIT),
    enabled: mode === 'all',
    placeholderData: keepPreviousData,
  });

  // 맞춤 추천: 서버 추천 결과와 우선순위·완화 제안·피드백을 함께 다룬다.
  const board = useRecommendationBoard(filter, canRecommend);

  const pref = useQuery({ queryKey: ['preference'], queryFn: profileApi.getPreference, retry: false, enabled: !!user });

  const houses = mode === 'all' ? (allQuery.data?.houses ?? []) : board.ranked;
  const pins = useMemo(() => toPins(houses), [houses]);

  // 좌표가 없어 핀을 못 찍는 수와, 서버 조회 상한에 걸려 아예 못 받은 수는 원인이 달라 따로 센다.
  const withoutCoords = houses.length - pins.length;
  const truncated = mode === 'all' ? Math.max(0, (allQuery.data?.total ?? 0) - houses.length) : 0;

  const detailQuery = useQuery({
    queryKey: ['map-housing', selectedId],
    queryFn: () => getMapHousingDetail(selectedId!),
    enabled: selectedId != null,
  });

  // 추천 모드에서 고른 집이면 점수·근거를 덧붙인다. 목록에 이미 있는 값이라 추가 요청이 없다.
  const selectedScore = mode === 'recommended' ? (board.ranked.find((h) => h.houseId === selectedId) ?? null) : null;

  const loading = mode === 'all' ? allQuery.isLoading : board.query.isLoading;
  const failed = mode === 'all' ? allQuery.isError : board.query.isError;

  return (
    <div className="map-page">
      <div className="map-toolbar">
        <nav className="map-modes" aria-label="지도 보기 모드">
          <button type="button" className={`chip ${mode === 'all' ? 'chip--on' : ''}`} onClick={() => patch({ mode: null })}>
            전체 공고
          </button>
          <button
            type="button"
            className={`chip ${mode === 'recommended' ? 'chip--on' : ''}`}
            onClick={() => patch({ mode: 'recommended' })}
          >
            맞춤 추천
          </button>
        </nav>

        <span className="map-toolbar__count">
          {loading && '불러오는 중…'}
          {!loading && mode === 'all' && allQuery.data && (
            <>
              조건에 맞는 집 <strong>{allQuery.data.total}곳</strong> · 지도 표시 {pins.length}곳
            </>
          )}
          {!loading && mode === 'recommended' && board.query.data && (
            <>
              추천 <strong>{board.ranked.length}곳</strong> · 지도 표시 {pins.length}곳
            </>
          )}
        </span>

        {mode === 'recommended' && canRecommend && (
          <Link to="/tune" className="btn btn--ghost btn--small">
            취향 찾기
          </Link>
        )}

        <details className="map-filter">
          <summary>필터</summary>
          <div className="map-filter__body">
            <FilterPanel
              filter={filter}
              onChange={setFilter}
              resultCount={mode === 'all' ? (allQuery.data?.total ?? 0) : board.ranked.length}
              regionOptions={pref.data?.regions ?? []}
            />
          </div>
        </details>
      </div>

      {failed && <p className="error">공급주택 데이터를 불러오지 못했습니다.</p>}
      {(withoutCoords > 0 || truncated > 0) && (
        <p className="muted small map-page__notice">
          {withoutCoords > 0 && `주소 좌표를 아직 확인하지 못한 ${withoutCoords}곳은 지도에 표시되지 않아요. `}
          {truncated > 0 && `조회 상한에 걸려 ${truncated}곳은 아직 불러오지 못했어요. 지역·조건을 좁히면 모두 볼 수 있어요.`}
        </p>
      )}

      <div className="map-workspace">
        <MapPanel pins={pins} selectedId={selectedId} onSelect={(id) => patch({ housing: String(id) })} className="map-panel" />

        <aside className="map-aside" aria-live="polite">
          {selectedId != null ? (
            <>
              <button type="button" className="map-aside__back" onClick={() => patch({ housing: null })}>
                ← {mode === 'recommended' ? '추천 목록' : '지도'}으로
              </button>
              {detailQuery.isLoading && <p className="map-side__notice">상세정보를 불러오는 중…</p>}
              {detailQuery.isError && <p className="map-side__notice">상세정보를 불러오지 못했습니다.</p>}
              {detailQuery.data && (
                <HousingDetail
                  detail={detailQuery.data}
                  showPageLink
                  score={selectedScore ? <SelectedScore house={selectedScore} /> : undefined}
                />
              )}
            </>
          ) : mode === 'recommended' ? (
            <RecommendationSidebar
              signedIn={!!user}
              onboarding={onboarding}
              filter={filter}
              board={board}
              onSelect={(id) => patch({ housing: String(id) })}
            />
          ) : (
            <p className="map-side__notice">지도에서 집을 선택하면 상세정보와 로드뷰가 표시됩니다.</p>
          )}
        </aside>
      </div>
    </div>
  );
}

/** 선택한 집의 추천 점수·근거. 추천 목록에 이미 들어 있는 값이라 추가 요청이 없다. */
function SelectedScore({ house }: { house: RecommendedHouse }) {
  return (
    <section className="map-score">
      <div className="map-score__head">
        {house.complete ? (
          <>
            추천도 <strong>{house.score}</strong>점
          </>
        ) : (
          <span className="badge badge--check">정보 부족 · 점수 없음</span>
        )}
        {house.eligibility === 'CHECK' && <span className="badge badge--check">지원조건 확인 필요</span>}
      </div>
      <ReasonList reasons={house.reasons} />
    </section>
  );
}
