import { api, unwrap } from './client';
import type {
  FeedbackReason,
  FeedbackResponse,
  FeedbackType,
  HouseFilter,
  RecommendationProfile,
  RecommendationProfileRequest,
  RecommendationResponse,
  RecommendedHouse,
  RelaxSuggestionResponse,
} from '../types';

/** 필터 객체를 쿼리 파라미터로 바꾼다. 빈 값은 보내지 않는다. */
export function filterToParams(f: HouseFilter, preview = false): Record<string, string> {
  const p: Record<string, string> = {};
  const join = (k: string, v: string[]) => {
    if (v.length) p[k] = v.join(',');
  };
  join('regions', f.regions);
  join('housingTypes', f.housingTypes);
  join('statuses', f.statuses);
  join('agencies', f.agencies);
  join('rentalTypes', f.rentalTypes);
  for (const k of ['depositMin', 'depositMax', 'rentMin', 'rentMax', 'areaMin', 'areaMax'] as const) {
    const v = f[k];
    if (v !== undefined && v !== null && !Number.isNaN(v)) p[k] = String(v);
  }
  p.includeUnknown = String(f.includeUnknown);
  if (preview) p.preview = 'true';
  return p;
}

export const recommendationApi = {
  list: (filter: HouseFilter, preview: boolean) =>
    unwrap<RecommendationResponse>(
      api.get('/api/v1/recommendations', { params: filterToParams(filter, preview) }),
    ),
  one: (houseId: number) => unwrap<RecommendedHouse>(api.get(`/api/v1/recommendations/${houseId}`)),
  relax: (filter: HouseFilter) =>
    unwrap<RelaxSuggestionResponse>(
      api.get('/api/v1/recommendations/relax-suggestions', { params: filterToParams(filter) }),
    ),

  profiles: () => unwrap<RecommendationProfile[]>(api.get('/api/v1/recommendation-profiles')),
  createProfile: (body: RecommendationProfileRequest) =>
    unwrap<RecommendationProfile>(api.post('/api/v1/recommendation-profiles', body)),
  updateProfile: (id: number, body: RecommendationProfileRequest) =>
    unwrap<RecommendationProfile>(api.patch(`/api/v1/recommendation-profiles/${id}`, body)),
  activateProfile: (id: number) =>
    unwrap<RecommendationProfile>(api.post(`/api/v1/recommendation-profiles/${id}/activate`)),
  deleteProfile: (id: number) => unwrap<void>(api.delete(`/api/v1/recommendation-profiles/${id}`)),

  feedback: (houseId: number, type: FeedbackType, reason?: FeedbackReason) =>
    unwrap<FeedbackResponse>(api.post('/api/v1/recommendation-feedbacks', { houseId, type, reason })),
  deleteFeedback: (id: number) => unwrap<void>(api.delete(`/api/v1/recommendation-feedbacks/${id}`)),
};
