import type { ScheduleEvent, ScheduleKind } from '../../types';

/** 캘린더에 올릴 일정 하나 = 관심 공고의 날짜(공고·접수 시작·접수 마감·당첨 발표) */
export type CalendarEvent = ScheduleEvent;

export const KIND_LABEL: Record<ScheduleKind, string> = {
  ANNOUNCED: '공고',
  APPLY_START: '접수 시작',
  APPLY_END: '접수 마감',
  WINNER: '당첨 발표',
};

export interface CalendarPanelProps {
  events: CalendarEvent[];
  /** 표시할 달 (YYYY-MM). 없으면 이번 달 */
  month?: string;
  onMonthChange?: (month: string) => void;
  onSelectEvent?: (event: CalendarEvent) => void;
  className?: string;
}
