import type { CalendarPanelProps } from './types';

/**
 * 캘린더 자리. 캘린더 담당자가 이 컴포넌트 본문을 달력 UI로 바꾸면 된다.
 * - props(CalendarPanelProps)와 export 이름은 유지한다.
 * - 일정은 toCalendarEvents()가 추천 응답에서 만들어 준다. 지금은 마감일(CLOSE)만 있다.
 */
export default function CalendarPanel({ events, onSelectEvent, className }: CalendarPanelProps) {
  return (
    <div className={`placeholder ${className ?? ''}`}>
      <strong>캘린더 영역</strong>
      <p>일정 {events.length}건이 준비돼 있어요. 캘린더 구현이 붙으면 여기에 표시돼요.</p>
      <ul className="placeholder__list">
        {events.map((e) => (
          <li key={e.id}>
            <button type="button" className="chip" onClick={() => onSelectEvent?.(e)}>
              {e.date} · {e.title} · 추천 {e.houseCount}곳 (최고 {e.topScore}점)
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
