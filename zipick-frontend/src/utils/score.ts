import { SCORE_ITEMS, type RecommendedHouse, type ScoreItem } from '../types';

/**
 * 서버와 같은 공식으로 추천도를 다시 계산한다. 가중치 슬라이더를 움직일 때 서버 없이 즉시 반영하기 위한 것.
 *
 *   가중치_i = 기본가중치_i × 우선순위_i   (계산 가능한 항목만, 합이 1이 되게 정규화)
 *   추천도   = 100 × Σ(가중치_i × 항목점수_i) × 지원조건 배수 (PASS 1.0, CHECK 0.7)
 */
export const CHECK_MULTIPLIER = 0.7;
export const MIN_SCORE = 60;
export const MAX_RESULTS = 30;
export const MAX_PER_ANNOUNCEMENT = 5;

export function normalizedWeights(
  base: Record<ScoreItem, number>,
  priorities: Record<ScoreItem, number>,
  itemScores: Partial<Record<ScoreItem, number | null>>,
): Partial<Record<ScoreItem, number>> {
  const raw: Partial<Record<ScoreItem, number>> = {};
  let sum = 0;
  for (const item of SCORE_ITEMS) {
    const s = itemScores[item];
    if (s === undefined || s === null) continue;
    const w = (base[item] ?? 0) * clamp(priorities[item] ?? 3, 1, 5);
    raw[item] = w;
    sum += w;
  }
  const out: Partial<Record<ScoreItem, number>> = {};
  for (const item of Object.keys(raw) as ScoreItem[]) {
    out[item] = sum === 0 ? 0 : (raw[item] ?? 0) / sum;
  }
  return out;
}

export function recalcScore(
  house: RecommendedHouse,
  base: Record<ScoreItem, number>,
  priorities: Record<ScoreItem, number>,
): number {
  const w = normalizedWeights(base, priorities, house.itemScores);
  let acc = 0;
  for (const item of Object.keys(w) as ScoreItem[]) {
    acc += (w[item] ?? 0) * (house.itemScores[item] ?? 0);
  }
  const mult = house.eligibility === 'PASS' ? 1 : house.eligibility === 'CHECK' ? CHECK_MULTIPLIER : 0;
  return Math.round(100 * acc * mult);
}

/** 서버의 순위 규칙과 동일: 60점 이상, 점수 내림차순(동점이면 마감 임박순), 공고당 최대 5곳, 최대 30곳 */
export function rankHouses(houses: RecommendedHouse[]): RecommendedHouse[] {
  const sorted = [...houses]
    .filter((h) => h.score >= MIN_SCORE)
    .sort((a, b) => {
      if (b.score !== a.score) return b.score - a.score;
      const ca = a.closeDate ?? '9999-12-31';
      const cb = b.closeDate ?? '9999-12-31';
      if (ca !== cb) return ca < cb ? -1 : 1;
      return a.houseId - b.houseId;
    });
  const perAnn = new Map<number, number>();
  const out: RecommendedHouse[] = [];
  for (const h of sorted) {
    if (out.length >= MAX_RESULTS) break;
    const n = perAnn.get(h.announcementId) ?? 0;
    if (n >= MAX_PER_ANNOUNCEMENT) continue;
    perAnn.set(h.announcementId, n + 1);
    out.push(h);
  }
  return out;
}

export function applyPriorities(
  houses: RecommendedHouse[],
  base: Record<ScoreItem, number>,
  priorities: Record<ScoreItem, number>,
): RecommendedHouse[] {
  return rankHouses(houses.map((h) => ({ ...h, score: recalcScore(h, base, priorities) })));
}

function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v));
}
