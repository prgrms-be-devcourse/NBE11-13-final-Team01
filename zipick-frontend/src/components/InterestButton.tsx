import { useInterest } from '../hooks/useInterest';

interface Props {
  noticeId: number;
  /** 글자 없이 ★ 만 */
  compact?: boolean;
}

/** 관심 공고 ★ 저장/해제. 저장한 공고는 일정 화면과 마감 알림의 대상이 된다. */
export default function InterestButton({ noticeId, compact }: Props) {
  const { enabled, isSaved, toggle, busy } = useInterest();
  if (!enabled) return null;
  const on = isSaved(noticeId);
  return (
    <button
      type="button"
      className={`btn btn--small ${on ? '' : 'btn--ghost'} interest-btn`}
      disabled={busy}
      onClick={() => toggle(noticeId)}
      aria-pressed={on}
      title={on ? '관심 공고에서 빼기' : '관심 공고로 저장 (일정·알림 대상)'}
    >
      {on ? '★' : '☆'}
      {!compact && (on ? ' 관심 공고' : ' 관심 공고 저장')}
    </button>
  );
}
