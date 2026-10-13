import type { RecommendedHouse } from '../../types';

/** 캘린더에 올릴 일정 하나. 지금은 공고 마감일만 만든다. 접수 시작·서류 제출·발표일은 공고 데이터가 생기면 추가한다. */
export interface CalendarEvent {
  id: string;
  announcementId: number;
  /** YYYY-MM-DD */
  date: string;
  kind: 'CLOSE' | 'OPEN' | 'DOCUMENT' | 'RESULT';
  title: string;
  /** 이 공고에서 추천된 집 수 */
  houseCount: number;
  topScore: number;
}

/** 캘린더 패널이 받는 props. 캘린더 담당자는 이 계약만 지키면 된다. */
export interface CalendarPanelProps {
  events: CalendarEvent[];
  /** 표시할 달. 없으면 이번 달 */
  month?: string;
  onSelectEvent?: (event: CalendarEvent) => void;
  className?: string;
}

/** 추천 응답을 공고 단위로 묶어 마감일 일정을 만든다. */
export function toCalendarEvents(houses: RecommendedHouse[]): CalendarEvent[] {
  const byAnnouncement = new Map<number, RecommendedHouse[]>();
  for (const h of houses) {
    if (!h.closeDate) continue;
    const list = byAnnouncement.get(h.announcementId) ?? [];
    list.push(h);
    byAnnouncement.set(h.announcementId, list);
  }
  return [...byAnnouncement.entries()]
    .map(([announcementId, list]) => ({
      id: `close-${announcementId}`,
      announcementId,
      date: list[0].closeDate!,
      kind: 'CLOSE' as const,
      title: `${list[0].title} 마감`,
      houseCount: list.length,
      topScore: Math.max(...list.map((h) => h.score)),
    }))
    .sort((a, b) => a.date.localeCompare(b.date));
}
