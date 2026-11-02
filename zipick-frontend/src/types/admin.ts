import type { NotificationEvent, UserPreference, UserProfile } from './index';

/** 운영자 API (/api/v1/admin/**) 응답 타입. 서버의 com.zipick.admin.* 레코드와 1:1 */

export interface PageResponse<T> {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  items: T[];
}

// ---- 수집 현황 ----
export type CollectionJobType = 'BASIC' | 'AI' | 'INFRA' | 'FULL';
export type RunStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';
export type RequestStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';

export interface InfraSummary { targets?: number; saved?: number; dataErrors?: number; apiErrors?: number; apiCalls?: number; aborted?: boolean; skipped?: boolean }
export interface RunSummary {
  // 기본 수집
  collectedRows?: number; createdNotices?: number; updatedNotices?: number; housings?: number; supplies?: number;
  missingClosedNotices?: number; inactiveHousings?: number; elapsedSeconds?: number;
  // AI 수집
  candidates?: number; processed?: number; detailed?: number; failed?: number; normalizationCandidates?: number;
  infra?: InfraSummary;
  raw?: string;
}
export interface CollectionRun {
  id: number;
  jobType: CollectionJobType;
  trigger: string;
  requestId: number | null;
  status: RunStatus;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  summary: RunSummary;
  errorMessage: string | null;
}
export interface CollectionRequest {
  id: number;
  jobType: CollectionJobType;
  status: RequestStatus;
  requestedBy: number;
  note: string | null;
  createdAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  resultMessage: string | null;
}
export interface CollectionOverview {
  lastRuns: Partial<Record<'BASIC' | 'AI' | 'INFRA', CollectionRun>>;
  running: CollectionRun[];
  openRequests: CollectionRequest[];
  data: {
    housings: number;
    activeHousings: number;
    housingsWithoutCoordinates: number;
    openHousings: number;
    openHousingsWithoutInfra: number;
    infraSnapshots: number;
    noticesByStatus: Record<string, number>;
    normalizationJobsByStatus: Record<string, number>;
    openAiDocumentIssues: number;
  };
}

export type IssueReason = 'incomplete' | 'empty' | 'exception';
export interface AiDocumentIssue {
  id: number;
  documentKey: string;
  agency: string | null;
  noticeTitle: string | null;
  fileName: string | null;
  reason: IssueReason;
  expectedUnits: number | null;
  parsedUnits: number | null;
  detail: string | null;
  occurrences: number;
  firstSeenAt: string;
  lastSeenAt: string;
  acknowledged: boolean;
  adminNote: string | null;
}

export type NormalizationStatus = 'PENDING' | 'RETRY' | 'SUCCESS';
export interface NormalizationJob {
  id: number;
  source: string;
  documentId: string;
  noticeTitle: string | null;
  status: NormalizationStatus;
  attemptCount: number;
  nextRetryAt: string | null;
  lastError: string | null;
  normalizedAt: string | null;
  normalizerVersion: string | null;
}

// ---- 공고·주택 ----
export type NoticeStatus = 'UPCOMING' | 'OPEN' | 'CLOSED' | 'CANCELLED';
export interface AdminNotice {
  id: number;
  source: string;
  externalNoticeId: string;
  title: string;
  agency: string | null;
  status: NoticeStatus;
  statusReason: string | null;
  region: string | null;
  noticeType: string | null;
  rentalType: string | null;
  applyStartAt: string | null;
  applyEndAt: string | null;
  detailUrl: string | null;
  lastSeenAt: string | null;
}
export interface AdminHousing {
  id: number;
  noticeId: number | null;
  noticeTitle: string | null;
  name: string | null;
  address: string;
  latitude: number | null;
  longitude: number | null;
  active: boolean;
  hasInfra: boolean;
  infraCollectedAt: string | null;
}

// ---- 회원 ----
export type Role = 'ROLE_USER' | 'ROLE_ADMIN';
export interface AdminUser {
  userId: number;
  email: string;
  role: Role;
  createdAt: string | null;
  hasProfile: boolean;
  hasPreference: boolean;
  onboarded: boolean;
  notificationEnabled: boolean;
  interestCount: number;
}
export interface AdminUserDetail {
  user: AdminUser;
  profile: UserProfile | null;
  preference: UserPreference | null;
  notificationSetting: {
    enabled: boolean; email: string | null; slackConfigured: boolean; marketingConsent: boolean;
    marketingConsentedAt: string | null; deadlineDays: string; newMatchEnabled: boolean;
  } | null;
  notifications: NotificationEvent[];
  notificationError: string | null;
}
export interface EmailVerificationRow {
  id: number;
  email: string;
  sentAt: string;
  expiresAt: string;
  attempts: number;
  sendCount: number;
  verifiedAt: string | null;
  expired: boolean;
}

// ---- 지표 ----
export interface AdminStats {
  users: {
    total: number; newLast7Days: number; newLast30Days: number; onboarded: number; onboardingRate: number;
    notificationEnabled: number; marketingConsented: number; interestNotices: number;
  };
  topRegions: { key: string; value: number }[];
  ai: { guidesLast7Days: number; guidesLast30Days: number; openDocumentIssues: number; normalizationJobsByStatus: Record<string, number> };
  collection: { runsLast7Days: number; failedLast7Days: number; infraSavedLast7Days: number; createdNoticesLast7Days: number };
}

// ---- 사용자 행동 (IP·화면 이동·클릭) ----
export type ActivityType = 'PAGE_VIEW' | 'CLICK' | 'LOGIN';
export interface ActivityEvent {
  id: number;
  userId: number | null;
  anonymousId: string | null;
  ip: string | null;
  userAgent: string | null;
  eventType: ActivityType;
  path: string;
  label: string | null;
  target: string | null;
  occurredAt: string;
}
export interface ActivityActor {
  userId: number | null;
  anonymousId: string | null;
  lastIp: string | null;
  lastSeen: string;
  firstSeen: string;
  events: number;
  pageViews: number;
  clicks: number;
  lastPath: string | null;
}
