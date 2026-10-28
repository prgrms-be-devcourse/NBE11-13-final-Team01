import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { getMapHousingDetail, getMapHousings } from '../api/mapHousing';
import FilterPanel from '../components/FilterPanel';
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
  const { filter, setFilter } = useHouseFilter();
  const { user } = useAuth();

  const mode: MapMode = params.get('mode') === 'recommended' ? 'recommended' : 'all';
  const parsed = Number(params.get('housing'));
  const selectedId = Number.isFinite(parsed) && parsed > 0 ? parsed : null;

  // 같은 좌표에 주택이 여러 곳일 때 고른 위치. URL 에는 넣지 않는다 —
  // housing 식별자·링크 의미(?housing=)를 바꾸지 않기 위해서다. 표시 방식일 뿐이다.
  const [locationIds, setLocationIds] = useState<number[] | null>(null);

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
        <MapPanel
          pins={pins}
          selectedId={selectedId}
          /* 목록이 열려 있는 동안 그 위치 마커를 선택 상태로 둔다 (상세로 들어가도 유지) */
          selectedLocationIds={locationIds}
          /* 모드가 바뀌면 지도에 펼쳐 둔 지역 상태도 푼다 */
          viewResetKey={mode}
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
            patch({ housing: String(id) });
          }}
          /* 위치 마커를 누르면 그 자리의 목록을 연다. 상세가 열려 있었다면 선택을 푼다 */
          onSelectLocation={(ids) => {
            setLocationIds(ids);
            if (selectedId != null) patch({ housing: null });
          }}
          className="map-panel"
        />

        <aside className="map-aside" aria-live="polite">
          {selectedId != null ? (
            <>
              {/* 위치 목록에서 들어왔으면 그 목록으로, 아니면 기존처럼 지도/추천 목록으로 */}
              <button type="button" className="map-aside__back" onClick={() => patch({ housing: null })}>
                ← {locationIds != null ? '이 위치 목록으로' : mode === 'recommended' ? '추천 목록으로' : '지도로'}
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
          ) : locationIds != null ? (
            <LocationList
              pins={pins.filter((pin) => locationIds.includes(pin.housingId))}
              onBack={() => setLocationIds(null)}
              onSelect={(id) => patch({ housing: String(id) })}
            />
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

/**
 * 같은 좌표에 주택이 여러 곳일 때 보여 주는 목록. 카드를 누르면 기존 상세 흐름으로 넘어간다.
 * 상세 조회·상세 패널은 그대로 재사용하므로 여기서는 고를 수 있게만 해 준다.
 */
/** 공고를 구분해 주는 한 줄. 값이 없는 항목은 빼고, 구분자가 앞뒤로 남지 않게 한다 */
function noticeLine(pin: MapPin): string {
  const parts: string[] = [];
  if (pin.announcedAt) parts.push(`공고일 ${pin.announcedAt}`);
  if (pin.applyEndAt) parts.push(`접수 마감 ${pin.applyEndAt}`);
  if (pin.noticeId != null) parts.push(`공고 ${pin.noticeId}번`);
  return parts.join(' · ');
}

function LocationList({ pins, onBack, onSelect }: { pins: MapPin[]; onBack: () => void; onSelect: (id: number) => void }) {
  return (
    <div className="map-side">
      <button type="button" className="map-aside__back" onClick={onBack}>
        ← 지도로
      </button>
      <p className="map-side__summary">
        이 위치의 주택 <strong>{pins.length}개</strong>
      </p>
      <p className="muted small">
        같은 자리에 여러 공고가 있어요. <strong>공고마다 별도 신청 기회</strong>이니 아래에서 구분해 확인하세요.
      </p>
      {pins.map((pin) => (
        <article key={pin.housingId} className="rec-item">
          <button type="button" className="rec-item__main" onClick={() => onSelect(pin.housingId)}>
            <span className="rec-item__body">
              <span className="rec-item__badges">
                {pin.agencyName && <span className="badge">{pin.agencyName}</span>}
                {pin.rentalType && <span className="badge">{pin.rentalType}</span>}
                {pin.status && <span className="badge">{STATUS_LABEL[pin.status as keyof typeof STATUS_LABEL] ?? pin.status}</span>}
              </span>
              <strong>{pin.title}</strong>
              <span className="muted small">{pin.address}</span>
              {/*
                같은 건물에 몇 주 간격으로 올라온 공고는 제목·기관·유형이 모두 같을 수 있다.
                그때 사용자가 서로 다른 신청 기회임을 알아볼 수 있는 값이 공고일·공고번호뿐이다.
              */}
              {pin.noticeTitle && pin.noticeTitle !== pin.title && (
                <span className="muted small">{pin.noticeTitle}</span>
              )}
              <span className="muted small location-list__notice">{noticeLine(pin)}</span>
            </span>
            <span className="rec-item__score" aria-hidden="true">›</span>
          </button>
        </article>
      ))}
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
