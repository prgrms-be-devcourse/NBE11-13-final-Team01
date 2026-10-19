import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { houseApi } from '../api/house';
import { profileApi } from '../api/profile';
import FilterPanel from '../components/FilterPanel';
import ModeSwitch from '../components/ModeSwitch';
import InterestButton from '../components/InterestButton';
import { Link } from 'react-router-dom';
import { useHouseFilter } from '../hooks/useHouseFilter';
import type { HouseSummary } from '../types';
import { STATUS_LABEL, area, dDay, housingLabel, regionText, rentalLabel, won } from '../utils/format';

/** 전체 공급주택: 점수 없이 필터만 적용한 목록. 로그인 전에도 볼 수 있다. */
export default function AllHousesPage() {
  const { filter, setFilter, search } = useHouseFilter();
  const list = useQuery({ queryKey: ['houses', filter], queryFn: () => houseApi.list(filter), placeholderData: keepPreviousData });
  // 관심 지역 칩은 내 조건이 있을 때만 보여 준다
  const pref = useQuery({ queryKey: ['preference'], queryFn: profileApi.getPreference, retry: false });

  return (
    <div className="layout">
      <aside className="sidebar">
        <FilterPanel
          filter={filter}
          onChange={setFilter}
          resultCount={list.data?.total ?? 0}
          regionOptions={pref.data?.regions ?? []}
        />
      </aside>

      <section className="content">
        <ModeSwitch search={search} />
        <div className="content__head">
          <h1>전체 공급주택</h1>
          {list.data && (
            <p className="muted">
              조건에 맞는 집 <strong>{list.data.total}곳</strong>
              {list.data.total > list.data.houses.length && ` (마감 임박순 ${list.data.houses.length}곳 표시)`}
            </p>
          )}
        </div>

        {list.isLoading && <p className="muted">불러오는 중…</p>}
        {list.isError && <div className="error">{(list.error as Error).message}</div>}
        {list.data && list.data.houses.length === 0 && (
          <div className="empty">
            <h2>조건에 맞는 집이 없어요</h2>
            <p>필터를 풀어 보세요.</p>
          </div>
        )}

        <div className="cards">
          {list.data?.houses.map((h) => <SummaryCard key={h.houseId} house={h} />)}
        </div>
      </section>
    </div>
  );
}

function SummaryCard({ house: h }: { house: HouseSummary }) {
  const d = dDay(h.closeDate);
  return (
    <article className="card">
      <div className="card__head">
        <span className={`badge badge--${h.status.toLowerCase()}`}>{STATUS_LABEL[h.status]}</span>
        {d && <span className="badge badge--soon">{d}</span>}
        <span className="card__agency">
          {h.agencyName ?? h.agencyCode} · {rentalLabel(h)}
        </span>
      </div>
      <h3 className="card__title">
        <Link to={`/houses/${h.houseId}`}>{h.housingName || h.address}</Link>
      </h3>
      <p className="card__addr">
        {regionText(h)}
        {h.housingName && ` · ${h.address}`}
        <span className="muted small"> · {h.title}</span>
      </p>
      <dl className="card__facts">
        <div><dt>보증금</dt><dd>{won(h.deposit)}</dd></div>
        <div><dt>월 임대료</dt><dd>{won(h.monthlyRent)}</dd></div>
        <div><dt>전용면적</dt><dd>{area(h.exclusiveArea)}</dd></div>
        <div><dt>방 수</dt><dd>{h.roomCount != null ? `${h.roomCount}개` : '정보 없음'}</dd></div>
        <div><dt>유형</dt><dd>{housingLabel(h)}</dd></div>
        <div><dt>호실</dt><dd>{h.supplyUnits}개</dd></div>
      </dl>
      <div className="card__actions">
        {h.detailUrl && (
          <a className="btn btn--ghost btn--small" href={h.detailUrl} target="_blank" rel="noreferrer">
            공고문 보기
          </a>
        )}
        <InterestButton noticeId={h.announcementId} />
      </div>
    </article>
  );
}
