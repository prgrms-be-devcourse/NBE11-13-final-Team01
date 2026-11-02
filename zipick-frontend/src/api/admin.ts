import { api, unwrap } from './client';
import type { NotificationEvent } from '../types';
import type {
  AdminHousing, AdminNotice, AdminStats, AdminUser, AdminUserDetail, AiDocumentIssue,
  CollectionJobType, CollectionOverview, CollectionRequest, CollectionRun, EmailVerificationRow,
  NormalizationJob, NormalizationStatus, NoticeStatus, PageResponse, Role,
  ActivityActor, ActivityEvent,
} from '../types/admin';

const BASE = '/api/v1/admin';

/** 운영자(ROLE_ADMIN)만. 권한은 서버가 확인한다 (아니면 403) */
export const adminApi = {
  // 수집 현황
  overview: () => unwrap<CollectionOverview>(api.get(`${BASE}/collections/overview`)),
  runs: (jobType?: CollectionJobType, page = 1, size = 20) =>
    unwrap<PageResponse<CollectionRun>>(api.get(`${BASE}/collections/runs`, { params: { jobType, page, size } })),
  abandonRun: (id: number) => unwrap<CollectionRun>(api.post(`${BASE}/collections/runs/${id}/abandon`)),
  requests: (page = 1, size = 20) =>
    unwrap<PageResponse<CollectionRequest>>(api.get(`${BASE}/collections/requests`, { params: { page, size } })),
  requestRun: (jobType: CollectionJobType, note?: string) =>
    unwrap<CollectionRequest>(api.post(`${BASE}/collections/requests`, { jobType, note: note || null })),
  cancelRequest: (id: number) => unwrap<CollectionRequest>(api.delete(`${BASE}/collections/requests/${id}`)),

  // AI 미완료 문서
  issues: (acknowledged: boolean, reason?: string, page = 1, size = 50) =>
    unwrap<PageResponse<AiDocumentIssue>>(api.get(`${BASE}/collections/ai-documents`, { params: { acknowledged, reason: reason || undefined, page, size } })),
  acknowledge: (id: number, note?: string) =>
    unwrap<AiDocumentIssue>(api.post(`${BASE}/collections/ai-documents/${id}/acknowledge`, { note: note || null })),
  reopen: (id: number) => unwrap<AiDocumentIssue>(api.delete(`${BASE}/collections/ai-documents/${id}/acknowledge`)),
  normalizationJobs: (status?: NormalizationStatus, page = 1, size = 50) =>
    unwrap<PageResponse<NormalizationJob>>(api.get(`${BASE}/collections/normalization-jobs`, { params: { status, page, size } })),
  requeue: (id: number) => unwrap<NormalizationJob>(api.post(`${BASE}/collections/normalization-jobs/${id}/requeue`)),

  // 공고·주택
  notices: (p: { status?: NoticeStatus[]; source?: string; q?: string; page?: number; size?: number }) =>
    unwrap<PageResponse<AdminNotice>>(api.get(`${BASE}/notices`, {
      params: { status: p.status?.length ? p.status.join(',') : undefined, source: p.source || undefined, q: p.q || undefined, page: p.page ?? 1, size: p.size ?? 30 },
    })),
  changeNoticeStatus: (id: number, status: NoticeStatus) => unwrap<AdminNotice>(api.patch(`${BASE}/notices/${id}/status`, { status })),
  housingsOf: (noticeId: number) => unwrap<AdminHousing[]>(api.get(`${BASE}/notices/${noticeId}/housings`)),
  unresolvedHousings: () => unwrap<AdminHousing[]>(api.get(`${BASE}/housings/unresolved`)),
  setCoordinates: (id: number, latitude: number, longitude: number) =>
    unwrap<AdminHousing>(api.patch(`${BASE}/housings/${id}/coordinates`, { latitude, longitude })),
  setHousingActive: (id: number, active: boolean) => unwrap<AdminHousing>(api.patch(`${BASE}/housings/${id}/active`, { active })),
  refreshInfra: (id: number) => unwrap<AdminHousing>(api.post(`${BASE}/housings/${id}/infra/refresh`)),

  // 회원
  users: (q?: string, page = 1, size = 30) =>
    unwrap<PageResponse<AdminUser>>(api.get(`${BASE}/users`, { params: { q: q || undefined, page, size } })),
  user: (id: number) => unwrap<AdminUserDetail>(api.get(`${BASE}/users/${id}`)),
  changeRole: (id: number, role: Role) => unwrap<AdminUser>(api.patch(`${BASE}/users/${id}/role`, { role })),
  verifications: () => unwrap<EmailVerificationRow[]>(api.get(`${BASE}/email-verifications`)),
  resendVerification: (email: string) =>
    unwrap<{ required: boolean }>(api.post(`${BASE}/email-verifications/${encodeURIComponent(email)}/resend`)),
  resetVerification: (email: string) => unwrap<void>(api.delete(`${BASE}/email-verifications/${encodeURIComponent(email)}`)),

  // 알림 이력 (Go 알림센터)
  notifications: (p: { source?: string; tag?: string; userId?: number }) =>
    unwrap<NotificationEvent[]>(api.get(`${BASE}/notifications`, { params: { source: p.source || undefined, tag: p.tag || undefined, userId: p.userId } })),
  notification: (id: number) => unwrap<NotificationEvent>(api.get(`${BASE}/notifications/${id}`)),
  channels: () => unwrap<Record<string, boolean> | null>(api.get(`${BASE}/notifications/channels`)),

  // 사용자 행동 (IP·화면 이동·클릭)
  actors: (hours = 24, page = 1, size = 30) =>
    unwrap<PageResponse<ActivityActor>>(api.get(`${BASE}/activity/actors`, { params: { hours, page, size } })),
  activityEvents: (p: { userId?: number; anonymousId?: string; ip?: string; type?: string; path?: string; page?: number; size?: number }) =>
    unwrap<PageResponse<ActivityEvent>>(api.get(`${BASE}/activity/events`, { params: { ...p, page: p.page ?? 1, size: p.size ?? 50 } })),
  trail: (p: { userId?: number; anonymousId?: string; limit?: number }) =>
    unwrap<ActivityEvent[]>(api.get(`${BASE}/activity/trail`, { params: p })),

  // 지표
  stats: () => unwrap<AdminStats>(api.get(`${BASE}/stats`)),
};
