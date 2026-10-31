import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import styles from './ApplicationGuidePage.module.css';
import '../design-system/tokens.css';
import {
  applicationGuideApi,
  CAUTION_KIND_LABEL,
  CHECK_STATUS_LABEL,
  CHECK_STATUS_BADGE,
  CHECK_STATUS_MARK,
  CHECK_STATUS_TONE,
  DOCUMENT_CATEGORY_HINT,
  DOCUMENT_CATEGORY_LABEL,
  DOCUMENT_CATEGORY_ORDER,
  formatSources,
  primarySourceFile,
  UNAVAILABLE_MESSAGE,
  type ApplicationGuide,
  type GuideDocumentItem,
  type GuideSourceRef,
} from '../api/applicationGuide';

/**
 * AI 맞춤 신청 준비 가이드 화면.
 *
 * 관심 공고를 저장한 뒤 "그래서 뭘 준비해야 하지?" 로 넘어가는 구간을 메우는 화면이라
 * 지도 패널 안이나 카드 안이 아니라 <b>독립된 페이지</b>로 둔다. 내용이 길고,
 * 사용자가 서류를 준비하는 동안 열어 두고 보는 화면이기 때문이다.
 *
 * AI 호출 시점은 사용자가 정한다.
 *   화면 진입 → GET (저장된 결과만, AI 호출 없음)
 *   버튼 클릭 → POST (이때만 실제로 분석한다)
 */
export default function ApplicationGuidePage() {
  const { noticeId } = useParams();
  const id = Number(noticeId);
  const valid = Number.isFinite(id) && id > 0;

  const queryClient = useQueryClient();
  const guideQuery = useQuery({
    queryKey: ['application-guide', id],
    queryFn: () => applicationGuideApi.get(id),
    enabled: valid,
    retry: false,
  });

  const generate = useMutation({
    mutationFn: () => applicationGuideApi.generate(id),
    onSuccess: (data) => queryClient.setQueryData(['application-guide', id], data),
  });

  if (!valid) return <div className="error">잘못된 주소예요.</div>;
  if (guideQuery.isLoading) return <p className="muted">불러오는 중…</p>;

  if (guideQuery.isError || !guideQuery.data) {
    const error = guideQuery.error as ApiError | null;
    return (
      <div className="detail">
        <Link to="/calendar" className="back">
          ← 관심 공고로
        </Link>
        <div className="empty">
          <h2>가이드를 열지 못했어요</h2>
          <p>{noticeAccessMessage(error)}</p>
          <Link className="btn" to="/calendar">
            관심 공고 보기
          </Link>
        </div>
      </div>
    );
  }

  const data = guideQuery.data;
  // 생성 직후에는 mutation 결과가 더 최신이다
  const current = generate.data ?? data;
  const generateError = generate.error as ApiError | null;
  // 성공 화면에는 기존 DOM 클래스와 스타일을 그대로 유지한다.
  const useStateDesign = generate.isPending || current.status !== 'READY' || !current.guide;

  return (
    <div className={useStateDesign ? `detail guide-page zipick-theme ${styles.page}` : 'detail guide-page'}>
      <Link to="/calendar" className="back">
        ← 관심 공고로
      </Link>

      <header className="guide-page__head">
        <p className="muted small">{current.agencyName ?? '공급기관 정보 없음'}</p>
        <h1>{current.noticeTitle}</h1>
        <p className="guide-page__subtitle">AI 맞춤 신청 준비 가이드</p>
      </header>

      {generateError && (
        <div className="error">{generateError.message ?? '가이드를 만들지 못했어요.'}</div>
      )}

      {generate.isPending ? (
        <GuideLoading />
      ) : current.status === 'READY' && current.guide ? (
        <GuideResultView data={current} />
      ) : current.status === 'UNAVAILABLE' ? (
        <GuideUnavailable data={current} onRetry={() => generate.mutate()} busy={generate.isPending} />
      ) : (
        <GuideNotGenerated onGenerate={() => generate.mutate()} busy={generate.isPending} />
      )}

      {useStateDesign ? (
        <GuideStateFooter data={current} showSource={generate.isPending || current.status !== 'UNAVAILABLE'} />
      ) : (
        <GuideFooter data={current} />
      )}
    </div>
  );
}

/** 아직 만든 적이 없는 상태. 여기서 자동으로 AI 를 부르지 않는다 */
function GuideNotGenerated({ onGenerate, busy }: { onGenerate: () => void; busy: boolean }) {
  return (
    <section className={styles.card} aria-labelledby="guide-before-title">
      <div className={styles.icon}>
        <GuideStateIcon kind="document" size={31} />
      </div>
      <h2 id="guide-before-title" className={styles.title}>복잡한 공고문, 핵심부터 확인해 보세요</h2>
      <p className={styles.description}>
        AI가 공고문을 살펴보고 신청 자격, 준비 서류, 꼭 확인할 내용을 항목별로 정리해 드려요.
      </p>
      <div className={styles.actions}>
        <button type="button" className={styles.primary} onClick={onGenerate} disabled={busy}>
          <GuideStateIcon kind="sparkle" size={17} />
          <span>AI 신청 준비 가이드 만들기</span>
          <GuideStateIcon kind="arrow" size={17} />
        </button>
      </div>
      <ul className={styles.features}>
        {['지원 조건 확인', '제출 서류 체크리스트', '신청 전 주의사항'].map((label) => (
          <li key={label}><GuideStateIcon kind="check" size={15} />{label}</li>
        ))}
      </ul>
    </section>
  );
}

/**
 * 생성 중. 예상 소요시간은 적지 않는다.
 * 공고문 길이에 따라 크게 달라져서 적어 두면 오히려 틀린 안내가 된다.
 */
function GuideLoading() {
  return (
    <section className={`${styles.card} ${styles.loading}`} aria-live="polite" aria-busy="true">
      {/* 몇 초 동안 화면이 멈춘 것처럼 보이지 않도록 진행 중이라는 신호를 둔다 */}
      <span className={`guide-loading__dots ${styles.dots}`} aria-hidden="true">
        <i /><i /><i />
      </span>
      <h2 className={`guide-loading__title ${styles.title}`}>공고문을 분석하고 있어요.</h2>
      <p className={styles.description}>
        신청 자격과 제출 서류, 놓치기 쉬운 주의사항을 정리하고 있어요.
      </p>
    </section>
  );
}

/** 만들지 못한 상태. 원문 링크와 기존 재시도 정책을 유지한다. */
function GuideUnavailable({
  data,
  onRetry,
  busy,
}: {
  data: ApplicationGuide;
  onRetry: () => void;
  busy: boolean;
}) {
  const message = data.reason === 'NO_DOCUMENT'
    ? '이 공고에서 분석할 수 있는 모집공고 파일을 찾지 못했어요.'
    : data.reasonMessage ?? (data.reason ? UNAVAILABLE_MESSAGE[data.reason] : '가이드를 만들지 못했어요.');
  // 공고문 자체를 읽을 수 없는 경우는 다시 눌러도 결과가 같다
  // (이미지 문서 / 분석할 파일 없음 / 지원하지 않는 형식)
  const NOT_RETRIABLE = ['SCANNED_DOCUMENT', 'NO_DOCUMENT', 'UNSUPPORTED_DOCUMENT_FORMAT'];
  const retriable = !data.reason || !NOT_RETRIABLE.includes(data.reason);

  return (
    <section className={`${styles.card} ${styles.failure}`} aria-labelledby="guide-failure-title">
      <div className={`${styles.icon} ${styles.failureIcon}`}>
        <GuideStateIcon kind="failure" size={31} />
      </div>
      <h2 id="guide-failure-title" className={styles.title}>신청 준비 가이드를 만들지 못했어요</h2>
      <p className={styles.description}>
        {message}
        <br />
        신청 자격과 접수 방법은 공고 원문에서 직접 확인해 주세요.
      </p>
      <div className={styles.actions}>
        {data.noticeDetailUrl && <GuideSourceLink url={data.noticeDetailUrl} primary />}
        <Link to="/calendar" className={styles.secondary}>관심 공고로 돌아가기</Link>
        {retriable && (
          <button type="button" className={styles.secondary} onClick={onRetry} disabled={busy}>
            다시 시도
          </button>
        )}
      </div>
    </section>
  );
}

/** 세 상태에서만 사용하는 장식 아이콘. 성공 화면과 공통 헤더에는 적용하지 않는다. */
function GuideStateIcon({ kind, size }: {
  kind: 'document' | 'failure' | 'sparkle' | 'arrow' | 'check' | 'external' | 'info';
  size: number;
}) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.85" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {kind === 'document' && <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h7M14 2v6h6m-6-6 6 6" /><circle cx="17" cy="17" r="3" /><path d="m19.2 19.2 2.2 2.2M8 12h5M8 16h3" /></>}
      {kind === 'failure' && <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V8l-8-6ZM14 2v6h8M12 12v4m0 3h.01" /></>}
      {kind === 'sparkle' && <path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Zm7 13 .8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8L19 16ZM4 2l.7 1.8L6.5 4.5l-1.8.7L4 7l-.7-1.8L1.5 4.5l1.8-.7L4 2Z" />}
      {kind === 'arrow' && <path d="M4 12h16m-7-7 7 7-7 7" />}
      {kind === 'check' && <><circle cx="12" cy="12" r="9" /><path d="m8 12 3 3 5-6" /></>}
      {kind === 'external' && <path d="M13 5h6v6m0-6-8 8M19 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5" />}
      {kind === 'info' && <><circle cx="12" cy="12" r="9" /><path d="M12 11v5m0-8h.01" /></>}
    </svg>
  );
}

function GuideSourceLink({ url, primary = false }: { url: string; primary?: boolean }) {
  return (
    <a className={primary ? styles.primary : styles.secondary} href={url} target="_blank" rel="noreferrer">
      <span>공고 원문 보기</span><GuideStateIcon kind="external" size={15} />
    </a>
  );
}

function GuideStateFooter({ data, showSource }: { data: ApplicationGuide; showSource: boolean }) {
  return (
    <footer className={styles.footer}>
      {showSource && data.noticeDetailUrl && <GuideSourceLink url={data.noticeDetailUrl} />}
      <p className={styles.disclaimer}>
        AI 안내는 신청 준비를 돕기 위한 참고정보예요. 최종 자격과 제출서류는 모집공고 원문을 확인해 주세요.
        {data.generatedAt && ` (${data.generatedAt.slice(0, 10)} 생성)`}
      </p>
    </footer>
  );
}

function GuideResultView({ data }: { data: ApplicationGuide }) {
  const guide = data.guide!;
  const sources = data.sources;
  // 결과가 한 공고문에서 나왔으면 파일명은 아래 한 줄로만 쓰고, 항목별 출처는 쪽수만 남긴다
  const sourceFile = primarySourceFile(sources);

  return (
    <>
      <section className="panel guide-section">
        <h2 className="panel__title">① 신청 준비 핵심</h2>
        <p className="guide-summary">{guide.summary}</p>
        {sourceFile && (
          <p className="guide-source-file">
            아래 내용은 <strong>{sourceFile}</strong> 에서 찾은 것이에요.
          </p>
        )}
      </section>

      {guide.personalChecks.length > 0 && (
        <section className="panel guide-section">
          <h2 className="panel__title">② 내 정보 기준 확인사항</h2>
          <ul className="guide-checks">
            {guide.personalChecks.map((check, index) => (
              <li key={`${check.title}-${index}`} className={`guide-check reason--${CHECK_STATUS_TONE[check.status]}`}>
                <span className="reason__mark guide-check__mark">{CHECK_STATUS_MARK[check.status]}</span>
                <div className="guide-check__body">
                  <div className="guide-check__head">
                    <strong>{check.title}</strong>
                    <span className={`badge ${CHECK_STATUS_BADGE[check.status]}`}>{CHECK_STATUS_LABEL[check.status]}</span>
                  </div>
                  <p className="guide-check__detail">{check.detail}</p>
                  <SourceLine sourceIds={check.sourceIds} sources={sources} omitFileName={sourceFile} />
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {guide.documents.length > 0 && (
        <section className="panel guide-section">
          <h2 className="panel__title">③ 준비할 서류</h2>
          {DOCUMENT_CATEGORY_ORDER.map((category) => {
            const items = guide.documents.filter((document) => document.category === category);
            if (items.length === 0) return null;
            return (
              <div key={category} className="guide-docs">
                <div className="guide-docs__head">
                  <h3>{DOCUMENT_CATEGORY_LABEL[category]}</h3>
                  <span className="muted small">{items.length}건</span>
                </div>
                <p className="hint">{DOCUMENT_CATEGORY_HINT[category]}</p>
                <ul className="guide-docs__list">
                  {items.map((document, index) => (
                    <DocumentRow
                      key={`${document.name}-${index}`}
                      document={document}
                      sources={sources}
                      omitFileName={sourceFile}
                      id={`doc-${category}-${index}`}
                    />
                  ))}
                </ul>
              </div>
            );
          })}
        </section>
      )}

      {guide.cautions.length > 0 && (
        <section className="panel guide-section">
          <h2 className="panel__title">④ 꼭 확인하세요</h2>
          <ul className="guide-cautions">
            {guide.cautions.map((caution, index) => (
              <li key={`${caution.kind}-${index}`} className="guide-caution">
                <span className={`badge badge--${caution.kind === 'MANUAL' ? 'check' : 'notice'}`}>
                  {CAUTION_KIND_LABEL[caution.kind]}
                </span>
                <div>
                  <p className="guide-caution__text">{caution.text}</p>
                  <SourceLine sourceIds={caution.sourceIds} sources={sources} omitFileName={sourceFile} />
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

/**
 * 서류 한 줄. 체크박스는 준비 상황을 눈으로 짚어 보라고 둔 것이고,
 * 체크 상태를 저장하지는 않는다(이번 범위 밖).
 */
function DocumentRow({
  document,
  sources,
  omitFileName,
  id,
}: {
  document: GuideDocumentItem;
  sources: Record<string, GuideSourceRef>;
  omitFileName: string | null;
  id: string;
}) {
  return (
    <li className="guide-doc">
      <input type="checkbox" id={id} className="guide-doc__check" />
      <div className="guide-doc__body">
        <label htmlFor={id} className="guide-doc__name">
          {document.name}
        </label>
        {document.reason && <p className="guide-doc__reason">{document.reason}</p>}
        {document.requirements && (
          <p className="guide-doc__requirements">준비 조건 · {document.requirements}</p>
        )}
        <SourceLine sourceIds={document.sourceIds} sources={sources} omitFileName={omitFileName} />
      </div>
    </li>
  );
}

/** 출처는 서버가 검증해 실제 파일·쪽으로 바꿔 준 값만 들어온다 */
function SourceLine({
  sourceIds,
  sources,
  omitFileName,
}: {
  sourceIds: string[];
  sources: Record<string, GuideSourceRef>;
  omitFileName: string | null;
}) {
  const label = formatSources(sourceIds, sources, omitFileName);
  if (!label) return null;
  return <p className="guide-source">출처 · {label}</p>;
}

function GuideFooter({ data }: { data: ApplicationGuide }) {
  return (
    <footer className="guide-foot">
      {data.noticeDetailUrl && (
        <a className="btn" href={data.noticeDetailUrl} target="_blank" rel="noreferrer">
          공고 원문 보기 ↗
        </a>
      )}
      <p className="muted small guide-foot__disclaimer">
        AI 안내는 신청 준비를 돕기 위한 참고정보예요. 최종 자격과 제출서류는 모집공고 원문을 확인해 주세요.
        {data.generatedAt && ` (${data.generatedAt.slice(0, 10)} 생성)`}
      </p>
    </footer>
  );
}

function noticeAccessMessage(error: ApiError | null) {
  if (error?.code === 'FORBIDDEN') return '관심 공고로 저장한 공고에서만 이용할 수 있어요.';
  if (error?.code === 'PROFILE_NOT_FOUND') return '내 정보를 먼저 입력하면 내 상황에 맞춰 정리해 드려요.';
  if (error?.code === 'ANNOUNCEMENT_NOT_FOUND') return '공고를 찾을 수 없어요. 접수가 끝났을 수 있어요.';
  return error?.message ?? '잠시 후 다시 시도해 주세요.';
}
