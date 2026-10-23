import { useInterest } from '../hooks/useInterest';
import { requireLogin } from '../hooks/useAuthGate';

interface Props {
  noticeId: number;
  /** 글자 없이 ★ 만 */
  compact?: boolean;
}

/**
 * 관심 공고 ★ 저장/해제. 저장한 공고는 일정 화면과 마감 알림의 대상이 된다.
 * 비회원에게도 보여 주고, 누르면 로그인 안내 팝업을 띄운다.
 * 이때 저장하려던 공고를 맡겨 두어 로그인 직후 이어서 저장된다(usePendingInterestRunner).
 */
export default function InterestButton({ noticeId, compact }: Props) {
  const { enabled, isSaved, toggle, busy } = useInterest();
  const on = enabled && isSaved(noticeId);

  const label = !enabled ? '관심 공고 저장' : on ? '관심 공고' : '관심 공고 저장';

  return (
    <button
      type="button"
      className={`btn btn--small ${on ? '' : 'btn--ghost'} interest-btn`}
      disabled={enabled && busy}
      onClick={() => (enabled ? toggle(noticeId) : requireLogin({ type: 'SAVE_INTEREST', noticeId }))}
      aria-pressed={on}
      title={
        !enabled
          ? '로그인하고 관심 공고로 저장하기 (일정·알림 대상)'
          : on
            ? '관심 공고에서 빼기'
            : '관심 공고로 저장 (일정·알림 대상)'
      }
    >
      {on ? '★' : '☆'}
      {!compact && ` ${label}`}
    </button>
  );
}
