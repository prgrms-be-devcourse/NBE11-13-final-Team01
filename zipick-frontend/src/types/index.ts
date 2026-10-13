// 백엔드 API 명세와 1:1로 맞춘 타입. 금액은 만 원, 면적은 ㎡.

export type Agency = 'LH' | 'SH' | 'GH' | 'HUG';
export type RentalType = 'PURCHASE' | 'HAPPY' | 'NATIONAL' | 'DEUNDEUN';
export type HousingType = 'APARTMENT' | 'MULTI_HOUSEHOLD' | 'OFFICETEL' | 'ROW_HOUSE' | 'ETC';
export type AnnouncementStatus = 'NOTICE' | 'RECEIVING' | 'CLOSED';
export type Mark = 'PASS' | 'PARTIAL' | 'CHECK' | 'FAIL';
export type Eligibility = 'PASS' | 'CHECK' | 'FAIL';
export type ScoreItem = 'cost' | 'region' | 'area' | 'infra' | 'type';
export const SCORE_ITEMS: ScoreItem[] = ['cost', 'region', 'area', 'infra', 'type'];

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error: { code: string; message: string; fields?: Record<string, string> } | null;
}

export interface Region {
  code: string;
  name: string;
  rank: number;
}

export interface UserProfile {
  birthDate: string;
  job?: string;
  married: boolean;
  householdSize: number;
  monthlyIncome?: number | null;
  totalAssets?: number | null;
  carValue?: number | null;
  homeless: boolean;
  /** 대학생·취업준비생 여부 (선택) */
  student?: boolean | null;
  /** 주택청약저축 가입 기간(개월)·납입 횟수 (선택) */
  subscriptionMonths?: number | null;
  subscriptionPayments?: number | null;
}

export interface UserPreference {
  regions: Region[];
  depositMin?: number | null;
  depositMax?: number | null;
  rentMin?: number | null;
  rentMax?: number | null;
  areaMin?: number | null;
  areaMax?: number | null;
  housingTypes: HousingType[];
  rentalTypes: RentalType[];
  monthlyBudget?: number | null;
}

export interface Reason {
  mark: Mark;
  item: string;
  text: string;
}

export interface RecommendedHouse {
  houseId: number;
  announcementId: number;
  title: string;
  agency: Agency;
  rentalType: RentalType;
  status: AnnouncementStatus;
  closeDate?: string;
  address: string;
  sigunguCode?: string;
  lat?: number;
  lng?: number;
  approximate: boolean;
  housingType?: HousingType;
  builtYear?: number;
  floor?: number;
  exclusiveArea?: number;
  deposit?: number;
  monthlyRent?: number;
  monthlyCost?: number;
  score: number;
  eligibility: Eligibility;
  /** 추천 제외 사유: ELIGIBILITY / coverage / MUST 항목 키. 추천되면 없음 */
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
  houses: RecommendedHouse[];
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
  agencies: Agency[];
  rentalTypes: RentalType[];
  includeUnknown: boolean;
}

export const EMPTY_FILTER: HouseFilter = {
  regions: [],
  housingTypes: [],
  statuses: ['NOTICE', 'RECEIVING'],
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

export interface HouseSummary {
  houseId: number;
  announcementId: number;
  title: string;
  agency: Agency;
  rentalType: RentalType;
  status: AnnouncementStatus;
  closeDate?: string;
  address: string;
  sigunguCode?: string;
  lat?: number;
  lng?: number;
  approximate: boolean;
  housingType?: HousingType;
  builtYear?: number;
  floor?: number;
  exclusiveArea?: number;
  deposit?: number;
  monthlyRent?: number;
}

export interface HouseListResponse {
  total: number;
  houses: HouseSummary[];
}

/** 필터 값별 공급주택 수. 0건(키 없음)인 값은 칩을 끈다 */
export interface FilterOptions {
  statuses: Partial<Record<AnnouncementStatus, number>>;
  agencies: Partial<Record<Agency, number>>;
  rentalTypes: Partial<Record<RentalType, number>>;
  housingTypes: Partial<Record<HousingType, number>>;
}
