import { api, unwrap } from './client';
import type { InterestNotice, ScheduleEvent } from '../types';

const BASE = '/api/v1/interest-notices';

/** 관심 공고 저장·해제·일정 */
export const interestApi = {
  list: () => unwrap<InterestNotice[]>(api.get(BASE)),
  ids: () => unwrap<number[]>(api.get(`${BASE}/ids`)),
  save: (noticeId: number, memo?: string) => unwrap<InterestNotice>(api.post(BASE, { noticeId, memo })),
  remove: (noticeId: number) => unwrap<void>(api.delete(`${BASE}/${noticeId}`)),
  schedule: (from?: string, to?: string) =>
    unwrap<ScheduleEvent[]>(api.get(`${BASE}/schedule`, { params: { from, to } })),
};
