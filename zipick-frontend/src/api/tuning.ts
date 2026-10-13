import { api, unwrap } from './client';
import type { AhpComparison, AhpResult, PairChoice, PairLearnResult, PairQuestion, RecommendationProfile } from '../types';

const BASE = '/api/v1/recommendation-tuning';

export const tuningApi = {
  /** apply=false 면 계산만(미리보기), true 면 활성 추천 기준에 저장 */
  ahp: (comparisons: AhpComparison[], apply: boolean) =>
    unwrap<AhpResult>(api.post(`${BASE}/ahp`, { comparisons, apply })),

  nextPair: () => unwrap<PairQuestion>(api.get(`${BASE}/pairs/next`)),
  choose: (leftHouseId: number, rightHouseId: number, choice: PairChoice) =>
    unwrap<PairLearnResult>(api.post(`${BASE}/pairs`, { leftHouseId, rightHouseId, choice })),
  result: () => unwrap<PairLearnResult>(api.get(`${BASE}/pairs/result`)),
  apply: () => unwrap<RecommendationProfile>(api.post(`${BASE}/pairs/apply`)),
  reset: () => unwrap<void>(api.delete(`${BASE}/pairs`)),
};
