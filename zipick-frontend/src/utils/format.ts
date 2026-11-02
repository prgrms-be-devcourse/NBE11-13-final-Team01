import type { AnnouncementStatus, HousingType, Mark, RentalType, ScoreItem } from '../types';

export const ITEM_LABEL: Record<ScoreItem, string> = {
  cost: '비용',
  region: '지역',
  area: '면적',
  rooms: '방 수',
  infra: '인프라',
  age: '노후도',
  type: '주택 유형',
};

export const RENTAL_LABEL: Record<RentalType, string> = {
  HAPPY: '행복주택',
  NATIONAL: '국민임대',
  PERMANENT: '영구임대',
  INTEGRATED: '통합공공임대',
  DEUNDEUN: '든든전세',
  JEONSE_LEASE: '전세임대',
  YOUTH_PURCHASE: '청년 매입임대',
  PURCHASE: '매입임대',
  PUBLIC: '공공임대(5·10년)',
};

export const HOUSING_LABEL: Record<HousingType, string> = {
  APARTMENT: '아파트',
  MULTI_HOUSEHOLD: '다세대·다가구',
  ROW_HOUSE: '연립',
  OFFICETEL: '오피스텔',
  URBAN_HOUSING: '도시형생활주택',
};

export const STATUS_LABEL: Record<AnnouncementStatus, string> = {
  UPCOMING: '공고중',
  OPEN: '접수중',
  CLOSED: '접수마감',
};

/** 공공임대 유형 표시. 분류 못 한 공고는 원본 공고 유형, 그것도 없으면 '유형 미확인' */
export function rentalLabel(h: { rentalType?: RentalType | null; noticeType?: string | null }): string {
  if (h.rentalType) return RENTAL_LABEL[h.rentalType];
  return h.noticeType?.trim() || '유형 미확인';
}

/** 주택 유형 표시. 분류 못 한 값('1형' 등)은 원본 그대로 */
export function housingLabel(h: { housingType?: HousingType | null; housingTypeRaw?: string | null }): string {
  if (h.housingType) return HOUSING_LABEL[h.housingType];
  return h.housingTypeRaw?.trim() || '정보 없음';
}

/** "시도 시군구" */
export function regionText(h: { province?: string | null; district?: string | null }): string {
  return [h.province, h.district].filter(Boolean).join(' ') || '지역 미확인';
}

export const MARK_SYMBOL: Record<Mark, string> = {
  PASS: '✓',
  PARTIAL: '△',
  CHECK: '?',
  FAIL: '✗',
};

export const REASON_LABEL = {
  EXPENSIVE: '너무 비쌈',
  REGION: '지역이 별로',
  SMALL: '너무 좁음',
  OLD: '오래됨',
  INFRA: '주변이 불편함',
  ETC: '기타',
} as const;

/** 만 원 → "1,500만 원" / 10000 이상은 "1억 2,000만 원" */
export function won(v?: number | null): string {
  if (v === undefined || v === null) return '정보 없음';
  if (v >= 10000) {
    const eok = Math.floor(v / 10000);
    const rest = v % 10000;
    return rest ? `${eok}억 ${rest.toLocaleString()}만 원` : `${eok}억 원`;
  }
  return `${v.toLocaleString()}만 원`;
}

export function area(v?: number | null): string {
  if (v === undefined || v === null) return '정보 없음';
  return `${v}㎡ (${(v / 3.3058).toFixed(1)}평)`;
}

/** 오늘부터 그 날짜까지 남은 일수. 날짜가 없거나 해석할 수 없으면 null */
export function daysUntil(date?: string | null): number | null {
  if (!date) return null;
  const target = new Date(date);
  if (Number.isNaN(target.getTime())) return null;
  target.setHours(0, 0, 0, 0);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((target.getTime() - today.getTime()) / 86_400_000);
}

export function dDay(date?: string): string {
  const diff = daysUntil(date);
  if (diff === null) return '';
  if (diff < 0) return '마감';
  if (diff === 0) return 'D-day';
  return `D-${diff}`;
}

/** "2026-10-15" → "2026.10.15" */
function dotted(date: string): string {
  return date.replaceAll('-', '.');
}

export interface ScheduleView {
  /** 배지에 쓰는 한 줄. 예: "접수 시작 D-3", "마감 D-5", "오늘 마감", "접수 일정 미정" */
  label: string;
  /** 알 수 있는 접수 기간. 예: "2026.10.01 ~ 2026.10.15". 모르면 null */
  period: string | null;
  /** 마감 임박(3일 이내)이라 강조할지 */
  urgent: boolean;
}

/**
 * 공고의 접수 일정 안내. 모집 상태에 따라 기준 날짜가 다르다.
 * - UPCOMING: 접수 시작일까지 D-day (시작일이 없으면 마감일로 안내)
 * - OPEN: 접수 마감일까지 D-day
 * 날짜가 없으면 임의 값을 만들지 않고 '미정'이라고 알린다.
 *
 * 지도 상세 패널·공고 상세 페이지·추천 목록이 모두 이 함수를 쓴다.
 */
export function applySchedule(
  status: AnnouncementStatus,
  applyStartAt?: string | null,
  applyEndAt?: string | null,
): ScheduleView {
  const period =
    applyStartAt && applyEndAt
      ? `${dotted(applyStartAt)} ~ ${dotted(applyEndAt)}`
      : applyStartAt
        ? `${dotted(applyStartAt)} 접수 시작`
        : applyEndAt
          ? `${dotted(applyEndAt)} 접수 마감`
          : null;

  const byEnd = (): ScheduleView => {
    const d = daysUntil(applyEndAt);
    if (d === null) return { label: '마감일 미정', period, urgent: false };
    if (d > 0) return { label: `마감 D-${d}`, period, urgent: d <= 3 };
    if (d === 0) return { label: '오늘 마감', period, urgent: true };
    return { label: '접수 마감', period, urgent: false };
  };

  if (status === 'CLOSED') return { label: '접수 마감', period, urgent: false };

  if (status === 'UPCOMING') {
    const d = daysUntil(applyStartAt);
    // 추천·목록 응답에는 마감일만 있어 시작일을 모를 때가 있다. 그때는 마감 기준으로 안내한다.
    if (d === null) return applyEndAt ? byEnd() : { label: '접수 일정 미정', period, urgent: false };
    if (d > 0) return { label: `접수 시작 D-${d}`, period, urgent: false };
    if (d === 0) return { label: '오늘 접수 시작', period, urgent: true };
    return byEnd();
  }

  return byEnd();
}
