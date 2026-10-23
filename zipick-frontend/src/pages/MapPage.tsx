import { useEffect, useMemo } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { getMapHousingDetail, getMapHousings } from '../api/mapHousing';
import FilterPanel from '../components/FilterPanel';
import HousingDetail from '../components/HousingDetail';
import ReasonList from '../components/ReasonList';
import RecommendationSidebar from '../components/map/RecommendationSidebar';
import { MapPanel, toMapPins, type MapPin } from '../features/map';
import { useAuth } from '../hooks/useAuth';
import { openGate } from '../hooks/useAuthGate';
import { useHouseFilter } from '../hooks/useHouseFilter';
import { useOnboardingStatus } from '../hooks/useOnboardingStatus';
import { useRecommendationBoard } from '../hooks/useRecommendationBoard';
import { EMPTY_FILTER, type RecommendedHouse } from '../types';

/**
 * 지도 한 화면에서 전체 공고와 맞춤 추천을 전환해 탐색한다.
 * 모드·선택한 핀·필터는 모두 URL 쿼리에 있어 로그인하러 갔다 와도 같은 화면으로 돌아온다.
 *
 *   /map                         전체 공고
 *   /map?mode=recommended        맞춤 추천 (로그인 + 내 조건 필요)
 *   /map?housing=123             123번 주택 상세 패널 열림
 */

type MapMode = 'all' | 'recommended';

/** 추천 결과 → 지도 핀. 좌표가 없는 주택은 핀을 만들 수 없어 빠진다(목록에는 그대로 남는다). */
function recommendationPins(houses: RecommendedHouse[]): MapPin[] {
  return houses
    .filter((h) => h.lat != null && h.lng != null)
    .map((h) => ({
      housingId: h.houseId,
      title: h.housingName || h.title,
      address: h.address,
      lat: h.lat!,
      lng: h.lng!,
    }));
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

  // 전체 공고: 지도 전용 API. 주택 단위라 핀이 중복되지 않고, 좌표 없는 주택은 서버가 빼 준다.
  const allQuery = useQuery({
    queryKey: ['map-housings', filter],
    queryFn: () => getMapHousings(filter),
    enabled: mode === 'all',
    placeholderData: keepPreviousData,
  });

  // 맞춤 추천: 저장된 내 조건과 추천 기준으로만 계산한다.
  // ⚠️ 전체 공고 모드의 탐색 필터(URL 쿼리)를 넘기면 안 된다. 추천 모드에는 필터 UI 가 없어
  //    사용자가 모르는 조건이 결과를 몰래 좁히게 된다. 그래서 빈 필터를 넘긴다.
  const board = useRecommendationBoard(EMPTY_FILTER, canRecommend);

  const pref = useQuery({ queryKey: ['preference'], queryFn: profileApi.getPreference, retry: false, enabled: !!user });

  const mapHousings = allQuery.data?.housings ?? [];
  const pins = useMemo(
    () => (mode === 'all' ? toMapPins(mapHousings) : recommendationPins(board.ranked)),
    [mode, mapHousings, board.ranked],
  );

  // 추천 결과에는 좌표 없는 주택이 섞인다. 목록 수와 핀 수가 달라지므로 그 차이를 알려 준다.
  // 전체 공고 모드는 서버가 좌표 없는 주택을 조회 단계에서 빼므로 그 수를 알 수 없다 → 안내하지 않는다.
  const unmappedRecommendations = mode === 'recommended' ? board.ranked.length - pins.length : 0;

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
              조건에 맞는 집 <strong>{allQuery.data.total}곳</strong>
            </>
          )}
          {!loading && mode === 'recommended' && board.query.data && (
            <>
              추천 <strong>{board.ranked.length}곳</strong>
              {unmappedRecommendations > 0 && ` · 지도 표시 ${pins.length}곳`}
            </>
          )}
        </span>

        {mode === 'recommended' && canRecommend && (
          <span className="map-toolbar__links">
            <Link to="/onboarding" className="btn btn--ghost btn--small">
              내 조건 수정
            </Link>
            <Link to="/tune" className="btn btn--ghost btn--small">
              취향 찾기
            </Link>
          </span>
        )}

        {/* 기본 필터는 전체 공고 모드에만 둔다. 추천 모드는 저장된 내 조건과 우선순위로 조정한다 */}
        {mode === 'all' && (
          <details className="map-filter">
            <summary>필터</summary>
            <div className="map-filter__body">
              <FilterPanel
                filter={filter}
                onChange={setFilter}
                resultCount={allQuery.data?.total ?? 0}
                regionOptions={pref.data?.regions ?? []}
              />
            </div>
          </details>
        )}
      </div>

      {failed && <p className="error">공급주택 데이터를 불러오지 못했습니다.</p>}
      {unmappedRecommendations > 0 && (
        <p className="muted small map-page__notice">
          추천 {unmappedRecommendations}곳은 아직 위치가 확인되지 않아 지도에 표시할 수 없어요. 오른쪽 목록에서 확인할 수
          있습니다.
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
                  filter={mode === 'all' ? filter : undefined}
                  score={selectedScore ? <SelectedScore house={selectedScore} /> : undefined}
                />
              )}
            </>
          ) : mode === 'recommended' ? (
            <RecommendationSidebar
              signedIn={!!user}
              onboarding={onboarding}
              /* 조건 완화 제안도 추천과 같은 기준이어야 한다. 전체 공고 모드의 탐색 필터를 넘기지 않는다 */
              filter={EMPTY_FILTER}
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
