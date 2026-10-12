// 백엔드 API 명세와 1:1로 맞춘 타입. 금액은 만 원, 면적은 ㎡.

export type Agency = 'LH' | 'SH' | 'GH' | 'HUG';
export type RentalType = 'PURCHASE' | 'HAPPY' | 'DEUNDEUN';
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
