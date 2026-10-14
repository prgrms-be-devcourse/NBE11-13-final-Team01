import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CalendarPanel, toCalendarEvents } from '../features/calendar';
import { useRecommendationList } from '../hooks/useRecommendationList';

/** 추천된 공고의 일정을 보는 화면. 달력 본체는 features/calendar/CalendarPanel이 담당한다. */
export default function CalendarPage() {
  const nav = useNavigate();
  const { data, isLoading, search } = useRecommendationList();
  const events = useMemo(() => toCalendarEvents(data?.houses ?? []), [data]);

  return (
    <div className="content">
      <div className="content__head">
        <h1>일정</h1>
        <p className="muted">
          추천 공고 {events.length}건의 마감일 ·{' '}
          <Link to={`/recommendations${search ? `?${search}` : ''}`}>목록으로</Link>
        </p>
      </div>
      {isLoading && <p className="muted">추천을 계산하는 중…</p>}
      <CalendarPanel
        events={events}
        onSelectEvent={(e) => nav(`/recommendations?announcementId=${e.announcementId}`)}
        className="calendar-panel"
      />
    </div>
  );
}
