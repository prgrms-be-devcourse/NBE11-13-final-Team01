// 백엔드 API 명세와 1:1로 맞춘 타입. 금액은 만 원, 면적은 ㎡.

/** 공공임대 유형. 백엔드 RentalType 과 같다 (공고 유형·제목에서 분류) */
export type RentalType =
  | 'HAPPY' | 'NATIONAL' | 'PERMANENT' | 'INTEGRATED' | 'DEUNDEUN' | 'JEONSE_LEASE'
  | 'YOUTH_PURCHASE' | 'PURCHASE' | 'PUBLIC';
export type HousingType = 'APARTMENT' | 'MULTI_HOUSEHOLD' | 'ROW_HOUSE' | 'OFFICETEL' | 'URBAN_HOUSING';
/** 모집 상태. 공고 수집 테이블의 NoticeStatus */
export type AnnouncementStatus = 'UPCOMING' | 'OPEN' | 'CLOSED';
export type Mark = 'PASS' | 'PARTIAL' | 'CHECK' | 'FAIL';
export type Eligibility = 'PASS' | 'CHECK' | 'FAIL';
/** 점수 항목. 순서는 화면 표시 순서 */
export type ScoreItem = 'cost' | 'region' | 'area' | 'rooms' | 'infra' | 'age' | 'type';
export const SCORE_ITEMS: ScoreItem[] = ['cost', 'region', 'area', 'rooms', 'infra', 'age', 'type'];

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error: { code: string; message: string; fields?: Record<string, string> } | null;
}

/** 관심 지역. code = "시도 시군구" (예: "경상북도 구미시"), name = 화면 표시용 */
export interface Region {
  code: string;
  name: string;
  rank: number;
}

/** 대학생·취업준비생 여부. 청년 매입임대는 연령 대신 이 자격으로도 신청할 수 있다 */
export type StudentStatus = 'NONE' | 'UNIVERSITY' | 'JOB_SEEKER';

/** 기본 정보. 모든 값이 지원조건 판정에 쓰인다 (docs/recommendation-fields.md) */
export interface UserProfile {
  birthDate: string;
  married: boolean;
  householdSize: number;
  monthlyIncome?: number | null;
  totalAssets?: number | null;
  carValue?: number | null;
  homeless: boolean;
  studentStatus: StudentStatus;
}

export interface UserPreference {
  regions: Region[];
  /** 보증금·월세 상한(만 원). 월 예산이 없을 때 예산 계산에 쓴다 */
  depositMax?: number | null;
  rentMax?: number | null;
  areaMin?: number | null;
  areaMax?: number | null;
  housingTypes: HousingType[];
  rentalTypes: RentalType[];
  monthlyBudget?: number | null;
  /** 희망 최소 방 수. 비우면 방 수 점수를 계산하지 않는다 */
  roomsMin?: number | null;
}

export interface Reason {
  mark: Mark;
  item: string;
  text: string;
}

/** 추천 결과 한 건 = 주택 하나(대표 호실 기준). houseId = 주택(housing) id */
export interface RecommendedHouse {
  houseId: number;
  supplyId: number;
  announcementId: number;
  title: string;
  agencyCode?: string;
  agencyName?: string;
  rentalType?: RentalType | null;
  noticeType?: string | null;
  status: AnnouncementStatus;
  closeDate?: string;
  detailUrl?: string;
  housingName?: string;
  address: string;
  province?: string;
  district?: string;
  lat?: number;
  lng?: number;
  housingType?: HousingType | null;
  housingTypeRaw?: string;
  buildYear?: number;
  floor?: number;
  exclusiveArea?: number;
  roomCount?: number;
  deposit?: number;
  monthlyRent?: number;
  monthlyCost?: number;
  score: number;
  /** 순위에 필요한 정보(비용·지역)가 다 있는지. false 면 '정보 부족' 묶음 */
  complete: boolean;
  eligibility: Eligibility;
  /** 추천 제외 사유: ELIGIBILITY / MUST 항목 키. 추천되면 없음 */
  excludedBy?: string | null;
  itemScores: Partial<Record<ScoreItem, number | null>>;
  reasons: Reason[];
}

export interface RecommendationResponse {
  profileId: number | null;
  profileVersion: number | null;
  baseWeights: Record<ScoreItem, number>;
  priorities: Record<ScoreItem, number>;
  total: number;
  candidateTotal: number;
  excluded: number;
  /** 순위 묶음: 점수로 비교한 결과 */
  houses: RecommendedHouse[];
  /** 정보 부족 묶음: 비용·지역 정보가 없어 점수로 비교하지 않은 주택. 관심 지역 → 마감 임박순 */
  incomplete: RecommendedHouse[];
}

export interface RecommendationProfile {
  id: number;
  name: string;
  active: boolean;
  version: number;
  weights: Partial<Record<ScoreItem, number>>;
  rules: Partial<Record<ScoreItem, 'MUST' | 'PREFER'>>;
  infraWeights: Record<string, number>;
}

export interface RecommendationProfileRequest {
  name?: string;
  weights?: Partial<Record<ScoreItem, number>>;
  rules?: Partial<Record<ScoreItem, 'MUST' | 'PREFER'>>;
  infraWeights?: Record<string, number>;
}

export type FeedbackType = 'HIDE' | 'DISLIKE';
export type FeedbackReason = 'EXPENSIVE' | 'REGION' | 'SMALL' | 'OLD' | 'INFRA' | 'ETC';

export interface FeedbackResponse {
  id: number;
  houseId: number;
  type: FeedbackType;
  reason?: FeedbackReason;
  hidden: boolean;
  suggestion?: { field: string; from: unknown; to: unknown; message: string } | null;
}

export interface RelaxSuggestion {
  field: string;
  change: string;
  countAfter: number;
}

export interface RelaxSuggestionResponse {
  currentCount: number;
  suggestions: RelaxSuggestion[];
}

export interface HouseFilter {
  regions: string[];
  depositMin?: number;
  depositMax?: number;
  rentMin?: number;
  rentMax?: number;
  areaMin?: number;
  areaMax?: number;
  housingTypes: HousingType[];
  statuses: AnnouncementStatus[];
  /** 기관 코드 */
  agencies: string[];
  rentalTypes: RentalType[];
  includeUnknown: boolean;
}

export const EMPTY_FILTER: HouseFilter = {
  regions: [],
  housingTypes: [],
  statuses: ['UPCOMING', 'OPEN'],
  agencies: [],
  rentalTypes: [],
  includeUnknown: true,
};

// ---- 우선순위 찾기 (AHP · 두 집 비교) ----

/** left 가 right 보다 2 훨씬 중요 / 1 더 중요 / 0 비슷 / -1 right 가 더 / -2 right 가 훨씬 */
export interface AhpComparison {
  left: ScoreItem;
  right: ScoreItem;
  value: -2 | -1 | 0 | 1 | 2;
}

export interface AhpResult {
  weights: Partial<Record<ScoreItem, number>>;
  priorities: Record<ScoreItem, number>;
  consistencyRatio: number;
  consistent: boolean;
  profile?: RecommendationProfile | null;
}

export type PairChoice = 'LEFT' | 'RIGHT' | 'TIE';

export interface PairQuestion {
  left: RecommendedHouse | null;
  right: RecommendedHouse | null;
  answered: number;
  minRequired: number;
}

export interface PairLearnResult {
  answered: number;
  minRequired: number;
  ready: boolean;
  currentPriorities: Record<ScoreItem, number>;
  learnedPriorities: Record<ScoreItem, number>;
  learnedWeights: Record<ScoreItem, number>;
  agreement?: number | null;
}

// ---- 전체 공급주택 (기본 필터링) ----

/** 전체 공급주택 목록의 한 건 = 주택 하나(점수 없음) */
export interface HouseSummary {
  houseId: number;
  supplyId: number;
  announcementId: number;
  title: string;
  agencyCode?: string;
  agencyName?: string;
  rentalType?: RentalType | null;
  noticeType?: string | null;
  status: AnnouncementStatus;
  closeDate?: string;
  detailUrl?: string;
  housingName?: string;
  address: string;
  province?: string;
  district?: string;
  lat?: number;
  lng?: number;
  housingType?: HousingType | null;
  housingTypeRaw?: string;
  buildYear?: number;
  exclusiveArea?: number;
  roomCount?: number;
  deposit?: number;
  monthlyRent?: number;
  /** 이 주택에서 필터를 통과한 공급 호실 수 */
  supplyUnits: number;
}

export interface HouseListResponse {
  total: number;
  houses: HouseSummary[];
}

export interface AgencyOption { code: string; name: string; count: number }
/** key = "시도 시군구" (필터·관심 지역에 쓰는 값) */
export interface RegionOption { key: string; province: string; district?: string | null; count: number }

/** 필터 칩과 관심 지역 선택지. 값마다 주택 수. 0건(키 없음)인 값은 칩을 끈다 */
export interface FilterOptions {
  statuses: Partial<Record<AnnouncementStatus, number>>;
  agencies: AgencyOption[];
  rentalTypes: Partial<Record<RentalType, number>>;
  housingTypes: Partial<Record<HousingType, number>>;
  regions: RegionOption[];
}

// ---- 관심 공고 · 일정 ----

export type ScheduleKind = 'ANNOUNCED' | 'APPLY_START' | 'APPLY_END' | 'WINNER';

export interface NoticeSummary {
  noticeId: number;
  title: string;
  noticeType?: string | null;
  rentalType?: RentalType | null;
  status: AnnouncementStatus;
  agencyName?: string;
  detailUrl?: string;
  announcedAt?: string | null;
  applyStartAt?: string | null;
  applyEndAt?: string | null;
  winnerAnnouncedAt?: string | null;
  housingCount: number;
  /** "시도 시군구" 최대 5개 */
  regions: string[];
}

export interface InterestNotice {
  id: number;
  notice: NoticeSummary;
  memo?: string | null;
  /** 접수 마감까지 남은 일수. 없으면 null, 지났으면 음수 */
  dDay?: number | null;
  savedAt?: string | null;
}

export interface ScheduleEvent {
  id: string;
  noticeId: number;
  title: string;
  kind: ScheduleKind;
  /** YYYY-MM-DD */
  date: string;
  dDay?: number | null;
  detailUrl?: string;
}

// ---- 알림 (관심 공고 마감 · 새 공고) ----

/** 알림센터 템플릿 코드. 운영자가 템플릿을 더 만들 수 있어 문자열로 둔다 */
export type NotificationStatus = 'PENDING' | 'SENT' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
export type NotificationChannel = 'email' | 'slack';

export interface NotificationSetting {
  userId?: number;
  enabled: boolean;
  slackConfigured: boolean;
  /** 웹훅은 비밀값이라 끝 6자만 온다 */
  slackWebhookMasked?: string | null;
  email?: string | null;
  /** 광고성 정보 수신 동의 (소식 메일 등). 동의 시각은 메인 서버가 보관 */
  marketingConsent: boolean;
  marketingConsentedAt?: string | null;
  deadlineDays: number[];
  newMatchEnabled: boolean;
  /** 알림센터에 이메일 발송 설정(SMTP/SES)이 있는지 */
  emailAvailable: boolean;
}

export interface NotificationSettingRequest {
  enabled: boolean;
  /** 비우면 기존 값을 지운다 */
  slackWebhookUrl?: string | null;
  email?: string | null;
  /** null 이면 그대로 */
  marketingConsent?: boolean | null;
  /** "3,1,0" */
  deadlineDays: string;
  newMatchEnabled: boolean;
}

/** 알림센터 v1 의 메시지. 채널별 결과는 deliveries 에 */
export interface NotificationDelivery {
  channel: NotificationChannel;
  status: 'PENDING' | 'SENT' | 'FAILED';
  attempts: number;
  lastError?: string | null;
  sentAt?: string | null;
}

export interface NotificationEvent {
  id: number;
  source: string;
  /** templates/*.yaml 의 코드 (NOTICE_DEADLINE_REMINDER, NOTICE_NEW_MATCH, MAIN_TEST, MARKETING_NEWSLETTER …) */
  templateCode: string;
  batchId?: number | null;
  channels: NotificationChannel[];
  title: string;
  status: NotificationStatus;
  deliveries: NotificationDelivery[];
  missingVariables: string[];
  tags: Record<string, string>;
  scheduledAt: string;
  createdAt: string;
  duplicate: boolean;
}

