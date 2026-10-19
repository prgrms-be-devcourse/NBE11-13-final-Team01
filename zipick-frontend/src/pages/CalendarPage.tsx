import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { interestApi } from '../api/interest';
import InterestButton from '../components/InterestButton';
import NotificationPanel from '../components/NotificationPanel';
import { CalendarPanel, KIND_LABEL } from '../features/calendar';
import { useAuth } from '../hooks/useAuth';
import { STATUS_LABEL, rentalLabel } from '../utils/format';

/** 관심 공고와 그 일정(공고일·접수 시작·마감·당첨 발표). 일정은 공고 데이터에서 그대로 가져온다. */
export default function CalendarPage() {
  const { user } = useAuth();
  const list = useQuery({ queryKey: ['interest-list'], queryFn: interestApi.list, enabled: !!user });
  const schedule = useQuery({ queryKey: ['interest-schedule'], queryFn: () => interestApi.schedule(), enabled: !!user });

  if (!user) {
    return (
      <div className="empty">
        <h2>로그인하면 관심 공고와 일정을 볼 수 있어요</h2>
        <p>추천 목록이나 전체 공급주택에서 ☆를 눌러 공고를 저장하세요.</p>
      </div>
    );
  }

  const upcoming = (schedule.data ?? []).filter((e) => (e.dDay ?? -1) >= 0).slice(0, 8);

  return (
    <div className="content calendar-page">
      <div className="content__head">
        <h1>관심 공고 · 일정</h1>
        <p className="muted">
          저장한 공고 {list.data?.length ?? 0}건 · 일정 {schedule.data?.length ?? 0}건 ·{' '}
          <Link to="/recommendations">추천 목록으로</Link>
        </p>
      </div>

      {upcoming.length > 0 && (
        <section className="panel upcoming">
          <h2 className="panel__title">다가오는 일정</h2>
          <ul className="upcoming__list">
            {upcoming.map((e) => (
              <li key={e.id}>
                <span className={`badge badge--soon`}>{e.dDay === 0 ? 'D-day' : `D-${e.dDay}`}</span>
                <span className={`cal__dot cal__dot--${e.kind.toLowerCase()}`}>{KIND_LABEL[e.kind]}</span>
                <span className="upcoming__title">{e.title}</span>
                <span className="muted small">{e.date}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <CalendarPanel
        events={schedule.data ?? []}
        onSelectEvent={(e) => {
          if (e.detailUrl) window.open(e.detailUrl, '_blank', 'noreferrer');
        }}
        className="calendar-panel"
      />

      <NotificationPanel />

      <section className="interest-list">
        <h2>저장한 공고</h2>
        {list.data && list.data.length === 0 && (
          <div className="empty">
            <h2>아직 저장한 공고가 없어요</h2>
            <p>추천 목록이나 전체 공급주택에서 ☆를 눌러 저장하면 여기에 일정이 표시돼요.</p>
          </div>
        )}
        <div className="cards">
          {list.data?.map((s) => (
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
      </section>
    </div>
  );
}
