import { useState } from 'react';
import type { CalendarEvent, CalendarPanelProps } from './types';
import { KIND_LABEL } from './types';

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

function ym(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}
function ymd(d: Date) {
  return `${ym(d)}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 월 달력. 날짜 칸마다 그날의 일정을 종류별 색으로 보여 준다. */
export default function CalendarPanel({ events, month, onMonthChange, onSelectEvent, className }: CalendarPanelProps) {
  const [inner, setInner] = useState(month ?? ym(new Date()));
  const current = month ?? inner;
  const setMonth = (m: string) => {
    setInner(m);
    onMonthChange?.(m);
  };
  const [y, m] = current.split('-').map(Number);
  const first = new Date(y, m - 1, 1);
  const last = new Date(y, m, 0);
  const todayKey = ymd(new Date());

  const byDate = new Map<string, CalendarEvent[]>();
  for (const e of events) {
    if (!byDate.has(e.date)) byDate.set(e.date, []);
    byDate.get(e.date)!.push(e);
  }

  const cells: (Date | null)[] = [];
  for (let i = 0; i < first.getDay(); i++) cells.push(null);
  for (let d = 1; d <= last.getDate(); d++) cells.push(new Date(y, m - 1, d));
  while (cells.length % 7 !== 0) cells.push(null);

  const shift = (delta: number) => setMonth(ym(new Date(y, m - 1 + delta, 1)));

  return (
    <div className={`cal ${className ?? ''}`}>
      <div className="cal__head">
        <button type="button" className="btn btn--ghost btn--small" onClick={() => shift(-1)} aria-label="이전 달">‹</button>
        <strong>{y}년 {m}월</strong>
        <button type="button" className="btn btn--ghost btn--small" onClick={() => shift(1)} aria-label="다음 달">›</button>
        <button type="button" className="btn btn--ghost btn--small" onClick={() => setMonth(ym(new Date()))}>오늘</button>
        <span className="cal__legend">
          {(Object.keys(KIND_LABEL) as (keyof typeof KIND_LABEL)[]).map((k) => (
            <span key={k} className={`cal__dot cal__dot--${k.toLowerCase()}`}>{KIND_LABEL[k]}</span>
          ))}
        </span>
      </div>
      <div className="cal__grid" role="grid">
        {WEEKDAYS.map((w) => (
          <div key={w} className="cal__weekday">{w}</div>
        ))}
        {cells.map((d, i) => {
          if (!d) return <div key={`e${i}`} className="cal__cell cal__cell--empty" />;
          const key = ymd(d);
          const list = byDate.get(key) ?? [];
          return (
            <div key={key} className={`cal__cell ${key === todayKey ? 'cal__cell--today' : ''}`}>
              <span className="cal__day">{d.getDate()}</span>
              {list.map((e) => (
                <button
                  key={e.id}
                  type="button"
                  className={`cal__event cal__event--${e.kind.toLowerCase()}`}
                  title={`${KIND_LABEL[e.kind]} · ${e.title}`}
                  onClick={() => onSelectEvent?.(e)}
                >
                  {KIND_LABEL[e.kind]} · {e.title}
                </button>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}
