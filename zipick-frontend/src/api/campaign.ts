import { api, unwrap } from './client';
import type { Campaign, CampaignRequest } from '../types';

const BASE = '/api/v1/admin/campaigns';

/** 운영자(ROLE_ADMIN)만. 소식 메일 캠페인: 초안 → 예약(동의자에게 묶음 요청) → 취소 */
export const campaignApi = {
  list: () => unwrap<Campaign[]>(api.get(BASE)),
  get: (id: number) => unwrap<Campaign>(api.get(`${BASE}/${id}`)),
  create: (req: CampaignRequest) => unwrap<Campaign>(api.post(BASE, req)),
  schedule: (id: number) => unwrap<Campaign>(api.post(`${BASE}/${id}/schedule`)),
  cancel: (id: number) => unwrap<Campaign>(api.delete(`${BASE}/${id}`)),
};
