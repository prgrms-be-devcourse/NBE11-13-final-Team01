import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { interestApi } from '../api/interest';
import InterestButton from '../components/InterestButton';
import NotificationPanel from '../components/NotificationPanel';
import { CalendarPanel, KIND_LABEL } from '../features/calendar';
import { useAuth } from '../hooks/useAuth';
import { STATUS_LABEL, rentalLabel } from '../utils/format';
import styles from '../features/calendar/CalendarPage.module.css';

/** 관심 공고와 그 일정(공고일·접수 시작·마감·당첨 발표). 일정은 공고 데이터에서 그대로 가져온다. */
export default function CalendarPage() {
  const { user } = useAuth();
  const list = useQuery({ queryKey: ['interest-list'], queryFn: interestApi.list, enabled: !!user });
  const schedule = useQuery({ queryKey: ['interest-schedule'], queryFn: () => interestApi.schedule(), enabled: !!user });

  // 로그인 확인은 라우트의 AccessGate가 한다. 여기 오면 user는 항상 있다.
  const upcoming = (schedule.data ?? []).filter((e) => (e.dDay ?? -1) >= 0).slice(0, 8);
  const [upcomingExpanded, setUpcomingExpanded] = useState(false);
  const [page, setPage] = useState(1);
  const pageCount = Math.max(1, Math.ceil((list.data?.length ?? 0) / 4));
  const currentPage = Math.min(page, pageCount);
  const visibleNotices = list.data?.slice((currentPage - 1) * 4, currentPage * 4);

  // 마지막 페이지의 공고를 해제하면 빈 페이지 대신 남은 마지막 페이지를 표시한다.
  useEffect(() => {
    if (list.data) setPage((previous) => Math.min(previous, pageCount));
  }, [list.data, pageCount]);

  return (
    <div className={`${styles.page} content calendar-page`}>
      <div className={styles.heading}>
        <div>
          <h1>관심 공고 · 일정</h1>
          <p className="muted">저장한 공고와 신청 일정을 한곳에서 확인하세요.</p>
          <p className={styles.counts}>
            저장한 공고 {list.data ? `${list.data.length}건` : list.isError ? '조회 실패' : '확인 중'} · 일정{' '}
            {schedule.data ? `${schedule.data.length}건` : schedule.isError ? '조회 실패' : '확인 중'}
          </p>
        </div>
        <Link className="btn btn--ghost" to="/recommendations">추천 목록으로</Link>
      </div>

      <div className={styles.scheduleLayout}>
        <section className="panel" aria-label="관심 공고 캘린더">
          <h2 className="panel__title">공고 캘린더</h2>
          {schedule.isPending && <p className={styles.state} role="status">일정을 불러오는 중이에요…</p>}
          {schedule.isError && <div className="error" role="alert">일정을 불러오지 못했어요. <button className="btn btn--ghost btn--small" onClick={() => schedule.refetch()}>다시 시도</button></div>}
          {schedule.data && schedule.data.length === 0 && <p className={styles.state}>표시할 일정이 없어요. 관심 공고를 저장하면 공고의 일정이 표시돼요.</p>}
          <CalendarPanel
            events={schedule.data ?? []}
            onSelectEvent={(e) => {
              if (e.detailUrl) window.open(e.detailUrl, '_blank', 'noreferrer');
            }}
            className="calendar-panel"
          />
        </section>
        <section className="panel upcoming">
          <h2 className="panel__title">다가오는 일정 {schedule.data && <span className={styles.sectionCount}>{upcoming.length}건</span>}</h2>
          <div className={styles.upcomingScroll} tabIndex={0} role="region" aria-label="다가오는 일정 목록">
            {schedule.isPending && <p className={styles.state} role="status">다가오는 일정을 확인하고 있어요…</p>}
            {schedule.isError && <p className={styles.state}>캘린더의 다시 시도로 일정을 확인해 주세요.</p>}
            {schedule.isSuccess && upcoming.length === 0 && <p className={styles.state}>다가오는 일정이 없어요.</p>}
            <ul id="upcoming-events" className={`upcoming__list ${upcomingExpanded ? '' : styles.collapsed}`}>
              {upcoming.map((e) => (
                <li key={e.id}>
                  <span className={`badge badge--soon`}>{e.dDay === 0 ? 'D-day' : `D-${e.dDay}`}</span>
                  <span className={`cal__dot cal__dot--${e.kind.toLowerCase()}`}>{KIND_LABEL[e.kind]}</span>
                  <span className="upcoming__title">{e.title}</span>
                  <span className="muted small">{e.date}</span>
                </li>
              ))}
            </ul>
          </div>
          {upcoming.length > 3 && (
            <button type="button" className={`btn btn--ghost btn--small ${styles.upcomingToggle}`} aria-expanded={upcomingExpanded} aria-controls="upcoming-events" onClick={() => setUpcomingExpanded((expanded) => !expanded)}>
              {upcomingExpanded ? '접기' : `더 보기 (${upcoming.length - 3}개)`}
            </button>
          )}
        </section>
      </div>

      <section className="interest-list">
        <h2>저장한 공고 {list.data && <span className={styles.sectionCount}>{list.data.length}건</span>}</h2>
        {list.isPending && <p className={styles.state} role="status">저장한 공고를 불러오는 중이에요…</p>}
        {list.isError && <div className="error" role="alert">저장한 공고를 불러오지 못했어요. <button className="btn btn--ghost btn--small" onClick={() => list.refetch()}>다시 시도</button></div>}
        {list.data && list.data.length === 0 && (
          <div className="empty">
            <h2>아직 저장한 공고가 없어요</h2>
            <p>추천 목록이나 전체 공급주택에서 ☆를 눌러 저장하면 여기에 일정이 표시돼요.</p>
          </div>
        )}
        <div className="cards">
          {visibleNotices?.map((s) => (
            <article key={s.id} className="card">
              <div className="card__head">
                <span className={`badge badge--${s.notice.status.toLowerCase()}`}>{STATUS_LABEL[s.notice.status]}</span>
                {s.dDay != null && s.dDay >= 0 && <span className="badge badge--soon">{s.dDay === 0 ? 'D-day' : `D-${s.dDay}`}</span>}
                {s.dDay != null && s.dDay < 0 && <span className="badge">마감</span>}
                <span className="card__agency">
                  {s.notice.agencyName} · {rentalLabel(s.notice)}
                </span>
              </div>
              <h3 className="card__title">{s.notice.title}</h3>
              <p className="card__addr">
                {s.notice.regions.join(', ')}
                {s.notice.housingCount > 0 && ` · 주택 ${s.notice.housingCount}곳`}
              </p>
              <dl className="card__facts">
                <div><dt>공고</dt><dd>{s.notice.announcedAt ?? '—'}</dd></div>
                <div><dt>접수 시작</dt><dd>{s.notice.applyStartAt ?? '—'}</dd></div>
                <div><dt>접수 마감</dt><dd>{s.notice.applyEndAt ?? '—'}</dd></div>
                <div><dt>당첨 발표</dt><dd>{s.notice.winnerAnnouncedAt ?? '—'}</dd></div>
              </dl>
              <div className="card__actions">
                {/*
                  AI 신청 준비의 주 진입점. 누른다고 바로 AI 를 부르지는 않고 가이드 화면으로만 옮긴다.
                  생성은 그 화면에서 사용자가 한 번 더 눌러야 시작된다(무료 사용량 보호).
                */}
                <Link className="btn btn--small" to={`/interest-notices/${s.notice.noticeId}/guide`}>
                  AI 신청 준비
                </Link>
                {s.notice.detailUrl && (
                  <a className="btn btn--ghost btn--small" href={s.notice.detailUrl} target="_blank" rel="noreferrer">
                    공고문 보기
                  </a>
                )}
                <InterestButton noticeId={s.notice.noticeId} />
              </div>
            </article>
          ))}
        </div>
        {list.data && list.data.length > 0 && (
          <nav className={styles.pagination} aria-label="저장한 공고 페이지">
            <button type="button" className="btn btn--ghost btn--small" disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)}>이전</button>
            <span aria-live="polite" aria-atomic="true">{currentPage} / {pageCount} 페이지</span>
            <button type="button" className="btn btn--ghost btn--small" disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)}>다음</button>
          </nav>
        )}
      </section>
      <NotificationPanel />
    </div>
  );
}
