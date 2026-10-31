import styles from '../features/map/MapPage.module.css';
import { useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { getMapHousingDetail, getMapHousings } from '../api/mapHousing';
import MapFilterDrawer from '../features/map/MapFilterDrawer';
import HousingDetail from '../components/HousingDetail';
import ReasonList from '../components/ReasonList';
import RecommendationSidebar from '../components/map/RecommendationSidebar';
import { MapPanel, toMapPins, type MapPin } from '../features/map';
import { STATUS_LABEL } from '../utils/format';
import { useAuth } from '../hooks/useAuth';
import { openGate } from '../hooks/useAuthGate';
import { useHouseFilter } from '../hooks/useHouseFilter';
import { useOnboardingStatus } from '../hooks/useOnboardingStatus';
import { useRecommendationBoard } from '../hooks/useRecommendationBoard';
import { EMPTY_FILTER, type HouseFilter, type RecommendedHouse } from '../types';

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
      // 추천 응답에는 이미 지역이 들어 있다. 전체 모드와 같은 군집 로직을 타려면 핀에도 실어야 한다.
      province: h.province,
      district: h.district,
      // 같은 좌표 처리(locationGroup)도 전체 모드와 같은 기준을 쓰게 식별 정보를 함께 넘긴다.
      // ⚠️ announcementId 가 공고 id 다 (houseId 는 주택 id).
      noticeId: h.announcementId,
      rentalType: h.rentalType,
      status: h.status,
      noticeTitle: h.title,
      agencyName: h.agencyName,
      applyEndAt: h.closeDate,          // 추천 응답의 마감일
      // 추천 응답에는 공고일이 없다. 없으면 카드에서 그 줄만 빠진다.
    }));
}

export default function MapPage() {
  const [params, setParams] = useSearchParams();
  const mode: MapMode = params.get('mode') === 'recommended' ? 'recommended' : 'all';
  const { filter, setFilter } = useHouseFilter({ omitStatuses: mode === 'all' });
  const queryClient = useQueryClient();
  const filterContextRef = useRef(params.toString());
  const pageActiveRef = useRef(true);
  filterContextRef.current = params.toString();
  useEffect(() => { pageActiveRef.current = true; return () => { pageActiveRef.current = false; }; }, []);
  const { user } = useAuth();

  const parsed = Number(params.get('housing'));
  const selectedId = Number.isFinite(parsed) && parsed > 0 ? parsed : null;

  // 같은 좌표에 주택이 여러 곳일 때 고른 위치. URL 에는 넣지 않는다 —
  // housing 식별자·링크 의미(?housing=)를 바꾸지 않기 위해서다. 표시 방식일 뿐이다.
  const [locationIds, setLocationIds] = useState<number[] | null>(null);
  const [selectionAction, setSelectionAction] = useState(0);
  const panelRef = useRef<HTMLElement>(null);
  const mapAreaRef = useRef<HTMLDivElement>(null);
  const hasPanel = selectedId != null || locationIds != null || mode === 'recommended';
  const selectHouse = (id: number) => {
    patch({ housing: String(id) });
    setSelectionAction((value) => value + 1);
  };
  useEffect(() => {
    if (!selectionAction || !window.matchMedia('(max-width: 1099px)').matches) return;
    panelRef.current?.scrollIntoView({
      block: 'start',
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    });
  }, [selectionAction]);
  const returnToMap = () => {
    if (window.matchMedia('(max-width: 1099px)').matches) {
      mapAreaRef.current?.scrollIntoView({ block: 'start' });
    }
  };

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
    // 적용 전 검증 요청의 캐시를 바로 재사용해 같은 조건을 중복 조회하지 않는다.
    staleTime: 1000,
  });

  const applyFilter = async (next: HouseFilter) => {
    const context = filterContextRef.current;
    // 성공한 결과를 확보한 뒤 URL을 확정한다. 실패하면 기존 필터와 핀을 유지한다.
    await queryClient.fetchQuery({ queryKey: ['map-housings', next], queryFn: () => getMapHousings(next), staleTime: 0, retry: false });
    if (!pageActiveRef.current || filterContextRef.current !== context) return false;
    setFilter(next);
    return true;
  };

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

  // 모드를 바꾸면 위치 목록은 닫는다. 상세를 열 때는 **지우지 않는다** —
  // 목록에서 들어간 경우 '뒤로'가 그 목록으로 돌아가야 하기 때문이다.
  useEffect(() => setLocationIds(null), [mode]);

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
    <div className={`map-page ${styles.page}`}>
      <div className="map-toolbar">
        <nav className="map-modes" aria-label="지도 보기 모드">
          <button type="button" className={`chip ${mode === 'all' ? 'chip--on' : ''}`} aria-pressed={mode === 'all'} onClick={() => patch({ mode: null })}>
            전체 공고
          </button>
          <button
            type="button"
            className={`chip ${mode === 'recommended' ? 'chip--on' : ''}`}
            aria-pressed={mode === 'recommended'} onClick={() => patch({ mode: 'recommended' })}
          >
            맞춤 추천
          </button>
        </nav>

        <span className="map-toolbar__count">
          {loading && '불러오는 중…'}
          {!loading && mode === 'all' && allQuery.data && (
            <>
              조건에 맞는 집 <strong>{allQuery.data.total.toLocaleString('ko-KR')}곳</strong>
            </>
          )}
          {!loading && mode === 'recommended' && board.query.data && (
            <>
              내 조건과 맞는{' '}
              <span className={styles.countGroup}>추천 주택 <strong>{board.ranked.length.toLocaleString('ko-KR')}곳</strong></span>
              {unmappedRecommendations > 0 && ` · 지도 표시 ${pins.length.toLocaleString('ko-KR')}곳`}
            </>
          )}
        </span>

        {mode === 'recommended' && canRecommend && (
          <span className="map-toolbar__links">
            <Link to="/onboarding" className="btn btn--ghost btn--small">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M4 7h8m4 0h4M4 17h4m4 0h8" />
                <circle cx="14" cy="7" r="2" />
                <circle cx="10" cy="17" r="2" />
              </svg>
              내 조건 수정
            </Link>
            <Link to="/tune" className="btn btn--ghost btn--small">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M12 3 9.8 9.8 3 12l6.8 2.2L12 21l2.2-6.8L21 12l-6.8-2.2z" />
              </svg>
              취향 찾기
            </Link>
          </span>
        )}

        {/* 기본 필터는 전체 공고 모드에만 둔다. 추천 모드는 저장된 내 조건과 우선순위로 조정한다 */}
        {mode === 'all' && (
          <MapFilterDrawer filter={filter} contextKey={params.toString()} onApply={applyFilter} regionOptions={pref.data?.regions ?? []} />
        )}
      </div>

      {failed && <p className="error">공급주택 데이터를 불러오지 못했습니다.</p>}
      {unmappedRecommendations > 0 && (
        <p className="muted small map-page__notice">
          추천 {unmappedRecommendations}곳은 아직 위치가 확인되지 않아 지도에 표시할 수 없어요. 오른쪽 목록에서 확인할 수
          있습니다.
        </p>
      )}

      <div ref={mapAreaRef} className={`map-workspace ${styles.workspace} ${styles.withPanel}`}>
        <MapPanel
          pins={pins}
          selectedId={selectedId}
          /* 목록이 열려 있는 동안 그 위치 마커를 선택 상태로 둔다 (상세로 들어가도 유지) */
          selectedLocationIds={locationIds}
          /* 데이터가 준비된 모드 진입마다 카메라와 지역 단계를 함께 맞춘다 */
          viewResetKey={mode}
          viewReady={mode === 'all'
            ? allQuery.isSuccess && !allQuery.isFetching && !allQuery.isPlaceholderData
            : canRecommend && board.query.isSuccess && !board.query.isFetching && !board.query.isPlaceholderData}
          emptyMessage={mode === 'recommended'
            ? '지도에 표시할 추천 주택의 좌표가 없습니다. 추천 목록을 확인해 주세요.'
            : undefined}
          /*
            행정구역 군집 클릭·'전국 보기' — 새 탐색을 시작했으니 이전 선택을 닫는다.
            다른 지역을 보고 있는데 우측에 이전 위치 목록이 남아 있으면 맥락이 어긋난다.
            ⚠️ 위치 목록 안의 상세 ↔ 목록 이동은 여기로 오지 않는다(그 흐름은 그대로 유지).
          */
          onExploreStart={() => {
            setLocationIds(null);
            if (selectedId != null) patch({ housing: null });
          }}
          /* 단독 핀을 직접 누른 경우. 이전 위치 목록 맥락은 버린다 → '뒤로'가 지도로 간다 */
          onSelect={(id) => {
            setLocationIds(null);
            selectHouse(id);
          }}
          /* 위치 마커를 누르면 그 자리의 목록을 연다. 상세가 열려 있었다면 선택을 푼다 */
          onSelectLocation={(ids) => {
            setSelectionAction((value) => value + 1);
            setLocationIds(ids);
            if (selectedId != null) patch({ housing: null });
          }}
          className="map-panel"
        />

        {!hasPanel && <p className={styles.hint}>지도에서 주택이나 지역을 선택해 정보를 확인하세요.</p>}
        <aside ref={panelRef} className={`map-aside ${!hasPanel ? styles.idlePanel : ''}`} aria-label={selectedId != null ? '주택 상세' : locationIds != null ? '이 위치의 주택' : mode === 'recommended' ? '맞춤 추천' : '주택 선택 안내'} aria-live="polite">
          {selectedId != null ? (
            <>
              {/* 위치 목록에서 들어왔으면 그 목록으로, 아니면 기존처럼 지도/추천 목록으로 */}
              <button type="button" className="map-aside__back" onClick={() => { patch({ housing: null }); if (locationIds == null && mode === 'all') returnToMap(); }}>
                ← {locationIds != null ? '이 위치 목록으로' : mode === 'recommended' ? '추천 목록으로' : '지도로'}
              </button>
              {detailQuery.isLoading && <p className="map-side__notice">상세정보를 불러오는 중…</p>}
              {detailQuery.isError && <p className="map-side__notice">상세정보를 불러오지 못했습니다.</p>}
              {detailQuery.data && (
                <HousingDetail
                  detail={detailQuery.data}
                  filter={mode === 'all' ? filter : undefined}
                  score={selectedScore ? <SelectedScore house={selectedScore} /> : undefined}
                />
              )}
            </>
          ) : locationIds != null ? (
            <LocationList
              pins={pins.filter((pin) => locationIds.includes(pin.housingId))}
              onBack={() => { setLocationIds(null); returnToMap(); }}
              onSelect={selectHouse}
            />
          ) : mode === 'recommended' ? (
            <RecommendationSidebar
              signedIn={!!user}
              onboarding={onboarding}
              /* 조건 완화 제안도 추천과 같은 기준이어야 한다. 전체 공고 모드의 탐색 필터를 넘기지 않는다 */
              filter={EMPTY_FILTER}
              board={board}
              onSelect={selectHouse}
            />
          ) : (
            <div className={styles.emptyPanel}>
              <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                <path d="m3 10 9-7 9 7v10H3zM9 20v-7h6v7" strokeLinejoin="round" />
              </svg>
              <h2>궁금한 주택을 선택해보세요</h2>
              <p>지도에서 주택을 선택하면<br />상세 정보와 주변 환경을 확인할 수 있어요.</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

/**
 * 같은 좌표에 주택이 여러 곳일 때 보여 주는 목록. 카드를 누르면 기존 상세 흐름으로 넘어간다.
 * 상세 조회·상세 패널은 그대로 재사용하므로 여기서는 고를 수 있게만 해 준다.
 */
function LocationList({ pins, onBack, onSelect }: { pins: MapPin[]; onBack: () => void; onSelect: (id: number) => void }) {
  return (
    <>
      <button type="button" className="map-aside__back" onClick={onBack}>
        ← 지도로
      </button>
      <div className="map-side">
      <h2 className="map-side__summary">이 위치의 주택 <strong>{pins.length}개</strong></h2>
      <p className="location-list__intro">같은 위치에 여러 주택이 공고되어 있어요. 아래에서 원하는 주택을 선택해 자세한 내용을 확인해 보세요.</p>
      {pins.map((pin) => (
        <article key={pin.housingId} className="rec-item location-list__card">
          <button type="button" className="rec-item__main" onClick={() => onSelect(pin.housingId)}>
            <span className="rec-item__body">
              <span className="rec-item__badges">
                {pin.status && <span className={`badge badge--${pin.status.toLowerCase()}`}>{STATUS_LABEL[pin.status as keyof typeof STATUS_LABEL] ?? pin.status}</span>}
                {pin.agencyName && <span className="badge location-list__agency">{pin.agencyName}</span>}
                {pin.rentalType && <span className="badge location-list__type">{pin.rentalType}</span>}
              </span>
              <strong>{pin.title}</strong>
              <span className="muted small location-list__address"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></svg><span>{pin.address}</span></span>
              {/*
                같은 건물에 몇 주 간격으로 올라온 공고는 제목·기관·유형이 모두 같을 수 있다.
                그때 사용자가 서로 다른 신청 기회임을 알아볼 수 있는 값이 공고일·공고번호뿐이다.
              */}
              {pin.noticeTitle && pin.noticeTitle !== pin.title && (
                <span className="muted small location-list__notice-title"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M7 3h7l4 4v14H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M14 3v5h4M9 12h6m-6 4h6"/></svg><span>{pin.noticeTitle}</span></span>
              )}
              {(pin.announcedAt || pin.applyEndAt) && (
                <span className="location-list__dates">
                  {pin.announcedAt && <span><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 10h18"/></svg>공고일 {pin.announcedAt}</span>}
                  {pin.applyEndAt && <span><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>접수 마감 {pin.applyEndAt}</span>}
                </span>
              )}
            </span>
            <span className="rec-item__score" aria-hidden="true">›</span>
          </button>
        </article>
      ))}
      </div>
    </>
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
