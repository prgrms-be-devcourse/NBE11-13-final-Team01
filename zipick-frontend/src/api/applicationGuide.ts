import { api, unwrap } from './client';

/**
 * AI 맞춤 신청 준비 가이드.
 *
 * 관심 공고로 저장한 공고에서만 쓸 수 있다(서버가 검증한다).
 * GET 은 저장된 결과만 읽고 AI 를 호출하지 않는다. 화면에 들어오기만 해도
 * 무료 사용량이 깎이면 안 되기 때문이다. 생성은 사용자가 버튼을 눌렀을 때 POST 로만 일어난다.
 */

const base = (noticeId: number) => `/api/v1/interest-notices/${noticeId}/application-guide`;

/** READY: 결과 있음 / NOT_GENERATED: 아직 만든 적 없음 / UNAVAILABLE: 만들 수 없음 */
export type GuideStatus = 'READY' | 'NOT_GENERATED' | 'UNAVAILABLE';

export type GuideUnavailableReason =
  | 'NO_DOCUMENT'
  | 'DOWNLOAD_FAILED'
  | 'EXTRACT_FAILED'
  | 'SCANNED_DOCUMENT'
  | 'UNSUPPORTED_DOCUMENT_FORMAT'
  | 'NO_RELEVANT_SECTION'
  | 'AI_NOT_CONFIGURED'
  | 'QUOTA_EXCEEDED'
  | 'AI_FAILED';

/** MATCHED: 현재 정보와 일치 / CHECK_REQUIRED: 추가 확인 필요 / NOT_APPLICABLE: 해당 없음 */
export type CheckStatus = 'MATCHED' | 'CHECK_REQUIRED' | 'NOT_APPLICABLE';

/** COMMON: 공통 / PERSONALIZED: 내 상황에 맞는 / CONDITIONAL: 조건부 */
export type DocumentCategory = 'COMMON' | 'PERSONALIZED' | 'CONDITIONAL';

/** NOTICE: 공고문에서 확인된 유의사항 / MANUAL: 직접 확인해야 하는 사항 */
export type CautionKind = 'NOTICE' | 'MANUAL';

export interface GuidePersonalCheck {
  title: string;
  status: CheckStatus;
  detail: string;
  sourceIds: string[];
}

export interface GuideDocumentItem {
  name: string;
  category: DocumentCategory;
  /** 이 사용자에게 왜 필요한지 */
  reason: string;
  /** 공고문에 적힌 발급 기준일·원본 여부 등. 공고문에 없으면 null */
  requirements: string | null;
  sourceIds: string[];
}

export interface GuideCaution {
  kind: CautionKind;
  text: string;
  sourceIds: string[];
}

export interface GuideResult {
  summary: string;
  personalChecks: GuidePersonalCheck[];
  documents: GuideDocumentItem[];
  cautions: GuideCaution[];
}

/** "S1" 이 가리키는 실제 위치. page 는 PDF 일 때만 채워진다 */
export interface GuideSourceRef {
  fileName: string;
  page: number | null;
  section: string | null;
}

export interface ApplicationGuide {
  noticeId: number;
  noticeTitle: string;
  agencyName: string | null;
  /** AI 가 실패해도 항상 채워진다. 사용자는 언제나 원문으로 갈 수 있어야 한다 */
  noticeDetailUrl: string | null;
  status: GuideStatus;
  reason: GuideUnavailableReason | null;
  reasonMessage: string | null;
  guide: GuideResult | null;
  sources: Record<string, GuideSourceRef>;
  model: string | null;
  generatedAt: string | null;
  /** 저장돼 있던 결과를 그대로 받은 것인지 (화면에 그대로 노출하지는 않는다) */
  cached: boolean;
}

export const applicationGuideApi = {
  /** 저장된 결과만 조회. AI 를 호출하지 않는다 */
  get: (noticeId: number) => unwrap<ApplicationGuide>(api.get(base(noticeId))),

  /**
   * 가이드 생성. 저장된 결과가 아직 유효하면 서버가 그것을 그대로 돌려준다.
   *
   * 공용 인스턴스의 기본 타임아웃(10초)으로는 모자란다. 공고문을 내려받아 텍스트를 뽑고
   * Gemini 응답까지 기다리는 요청이라 실측으로도 9초 가까이 걸렸고, 긴 공고문은 더 걸린다.
   * 여기서 끊어 버리면 서버는 결과를 만들어 저장했는데 사용자만 실패 화면을 보게 된다.
   */
  generate: (noticeId: number) =>
    unwrap<ApplicationGuide>(api.post(base(noticeId), null, { timeout: 120_000 })),
};

/** 내부 enum 을 화면에 그대로 내보내지 않는다 */
export const CHECK_STATUS_LABEL: Record<CheckStatus, string> = {
  MATCHED: '현재 정보와 일치',
  CHECK_REQUIRED: '추가 확인 필요',
  NOT_APPLICABLE: '현재 정보상 해당 없음',
};

export const CHECK_STATUS_MARK: Record<CheckStatus, string> = {
  MATCHED: '✓',
  CHECK_REQUIRED: '?',
  NOT_APPLICABLE: '—',
};

/**
 * 추천 근거(.reason--*)와 같은 색 규칙을 쓴다.
 *
 * CHECK_REQUIRED 를 주황(partial)에 둔 이유는, 이게 <b>사용자가 실제로 움직여야 하는</b>
 * 항목이기 때문이다. 처음엔 회색(check)에 뒀는데 화면에서 보니 '일치'(초록)보다 흐려서
 * 정작 할 일이 눈에 덜 띄었다. 반대로 '해당 없음'은 읽고 넘기면 되는 항목이라 회색이 맞다.
 */
export const CHECK_STATUS_TONE: Record<CheckStatus, string> = {
  MATCHED: 'pass',
  CHECK_REQUIRED: 'partial',
  NOT_APPLICABLE: 'check',
};

/** 배지 색. 강조는 '추가 확인 필요' 하나만 준다 (여러 개를 강조하면 아무것도 강조되지 않는다) */
export const CHECK_STATUS_BADGE: Record<CheckStatus, string> = {
  MATCHED: '',
  CHECK_REQUIRED: 'badge--check',
  NOT_APPLICABLE: '',
};

export const DOCUMENT_CATEGORY_LABEL: Record<DocumentCategory, string> = {
  COMMON: '공통 제출서류',
  PERSONALIZED: '내 상황에 맞는 서류',
  CONDITIONAL: '조건부 제출서류',
};

export const DOCUMENT_CATEGORY_HINT: Record<DocumentCategory, string> = {
  COMMON: '모든 신청자가 내는 서류예요.',
  PERSONALIZED: '입력한 내 정보를 기준으로 필요해 보이는 서류예요.',
  CONDITIONAL: '해당하는 경우에만 내는 서류예요. 내 상황에 맞는지 확인해 주세요.',
};

/** 화면에 그리는 순서 */
export const DOCUMENT_CATEGORY_ORDER: DocumentCategory[] = ['COMMON', 'PERSONALIZED', 'CONDITIONAL'];

export const CAUTION_KIND_LABEL: Record<CautionKind, string> = {
  NOTICE: '공고문 유의사항',
  MANUAL: '직접 확인',
};

/** 서버가 내려주는 reasonMessage 를 쓰되, 없을 때를 대비한 기본 문구 */
export const UNAVAILABLE_MESSAGE: Record<GuideUnavailableReason, string> = {
  NO_DOCUMENT: '이 공고에서 분석할 수 있는 모집공고문 파일을 찾지 못했어요.',
  DOWNLOAD_FAILED: '공고문 파일을 내려받지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  EXTRACT_FAILED: '공고문 파일을 읽지 못했어요.',
  SCANNED_DOCUMENT: '공고문이 이미지 형태라 내용을 읽지 못했어요. 원문에서 직접 확인해 주세요.',
  UNSUPPORTED_DOCUMENT_FORMAT:
    'HWPX 형식의 모집공고문은 현재 AI 분석을 지원하지 않아요. 원문 공고문에서 내용을 확인해주세요.',
  NO_RELEVANT_SECTION: '신청 준비에 필요한 영역을 자동으로 찾지 못했어요.',
  AI_NOT_CONFIGURED: 'AI 기능이 아직 준비되지 않았어요.',
  QUOTA_EXCEEDED: '현재 AI 분석 요청이 많아 잠시 사용할 수 없어요. 내일 다시 시도해 주세요.',
  AI_FAILED: 'AI 신청 준비 가이드를 생성하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
};

/**
 * 결과 전체가 한 공고문에서 나왔으면 그 파일명. 여러 파일이 섞였으면 null.
 *
 * 서버는 점수가 가장 높은 공고문 한 건만 분석하므로 사실상 항상 한 파일이다.
 * 그 경우 파일명을 화면 위쪽에 한 번만 쓰고, 항목마다 붙는 출처는 쪽수만 남긴다.
 */
export function primarySourceFile(sources: Record<string, GuideSourceRef>): string | null {
  const names = new Set(Object.values(sources).map((s) => s.fileName));
  return names.size === 1 ? [...names][0] : null;
}

/**
 * "4쪽, 6쪽" 또는 "모집공고.pdf 4쪽, 6쪽" 처럼 한 줄로.
 *
 * @param omitFileName 이 파일명과 모두 일치하면 파일명을 빼고 쪽수만 남긴다.
 *   파일명이 60자쯤 되는 공고가 흔한데, 그걸 항목마다(한 화면에 열몇 번) 반복하면
 *   좁은 화면에서 출처 한 줄이 두세 줄로 접히면서 정작 서류 이름보다 더 눈에 띈다.
 */
export function formatSources(
  sourceIds: string[],
  sources: Record<string, GuideSourceRef>,
  omitFileName?: string | null,
): string | null {
  const refs = sourceIds.map((id) => sources[id]).filter(Boolean);
  if (refs.length === 0) return null;

  const fileName = refs[0].fileName;
  const sameFile = refs.every((r) => r.fileName === fileName);
  if (sameFile) {
    const parts = refs.map((r) => (r.page != null ? `${r.page}쪽` : r.section)).filter(Boolean);
    if (parts.length === 0) return omitFileName === fileName ? null : fileName;
    return omitFileName === fileName ? parts.join(', ') : `${fileName} ${parts.join(', ')}`;
  }
  return refs.map((r) => (r.page != null ? `${r.fileName} ${r.page}쪽` : r.fileName)).join(', ');
}
