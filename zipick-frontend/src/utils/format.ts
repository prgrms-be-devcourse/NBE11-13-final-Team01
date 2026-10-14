import type { AnnouncementStatus, HousingType, Mark, RentalType, ScoreItem } from '../types';

export const ITEM_LABEL: Record<ScoreItem, string> = {
  cost: '비용',
  region: '지역',
  area: '면적',
  infra: '인프라',
  type: '유형',
};

export const RENTAL_LABEL: Record<RentalType, string> = {
  PURCHASE: '매입임대',
  HAPPY: '행복주택',
  NATIONAL: '국민임대',
  DEUNDEUN: '든든전세',
};

export const HOUSING_LABEL: Record<HousingType, string> = {
  APARTMENT: '아파트',
  MULTI_HOUSEHOLD: '다세대',
  OFFICETEL: '오피스텔',
  ROW_HOUSE: '연립',
  ETC: '기타',
};

export const STATUS_LABEL: Record<AnnouncementStatus, string> = {
  NOTICE: '공고중',
  RECEIVING: '접수중',
  CLOSED: '접수마감',
};

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

export function dDay(date?: string): string {
  if (!date) return '';
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(date);
  const diff = Math.round((target.getTime() - today.getTime()) / 86_400_000);
  if (diff < 0) return '마감';
  if (diff === 0) return 'D-day';
  return `D-${diff}`;
}
