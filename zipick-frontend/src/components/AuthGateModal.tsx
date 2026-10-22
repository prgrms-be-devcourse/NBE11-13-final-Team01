import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { closeGate, returnPath, useAuthGate } from '../hooks/useAuthGate';

type Variant = 'LOGIN' | 'LOGIN_INTEREST' | 'ONBOARDING';

const COPY: Record<Variant, { title: string; desc: string }> = {
  LOGIN: {
    title: '로그인이 필요해요',
    desc: '로그인하면 관심 공고를 저장하고 맞춤 추천을 받을 수 있어요.',
  },
  LOGIN_INTEREST: {
    title: '관심 공고를 저장하시겠어요?',
    desc: '로그인하면 관심 공고를 저장하고 청약 일정을 관리할 수 있어요.',
  },
  ONBOARDING: {
    title: '맞춤 추천을 이용하려면 내 조건이 필요해요',
    desc: '간단한 정보만 입력하고 나에게 맞는 주택을 추천받아보세요.',
  },
};

function HeartIcon() {
  return (
    <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor" aria-hidden="true">
      <path d="M12 21s-7.5-4.7-9.4-9A5.2 5.2 0 0 1 12 6.6 5.2 5.2 0 0 1 21.4 12c-1.9 4.3-9.4 9-9.4 9Z" />
    </svg>
  );
}

function DocumentIcon() {
  return (
    <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M6 3h8l4 4v14H6z" />
      <path d="M14 3v4h4M9 12h6M9 16h6" />
    </svg>
  );
}

/**
 * 로그인·온보딩 공통 안내 팝업. Layout에서 한 번만 렌더한다.
 * 어디서 띄웠든 현재 경로를 `from`으로 넘겨, 로그인 후 보던 화면으로 돌아오게 한다.
 * 보류 작업이 있으면 그 맥락에 맞는 문구를 쓴다(관심 공고 저장 등).
 */
export default function AuthGateModal() {
  const { gate, pending } = useAuthGate();
  const navigate = useNavigate();
  const location = useLocation();

  // 팝업이 떠 있는 동안 Esc로 닫는다.
  useEffect(() => {
    if (!gate) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeGate();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [gate]);

  if (!gate) return null;

  const variant: Variant =
    gate === 'LOGIN' ? (pending?.type === 'SAVE_INTEREST' ? 'LOGIN_INTEREST' : 'LOGIN') : 'ONBOARDING';
  const copy = COPY[variant];
  const from = returnPath(location);

  const go = (path: string) => {
    closeGate();
    navigate(path, { state: from ? { from } : undefined });
  };

  return (
    <div className="modal" role="dialog" aria-modal="true" aria-labelledby="auth-gate-title">
      <button type="button" className="modal__backdrop" aria-label="닫기" onClick={closeGate} />
      <div className="modal__card">
        <button type="button" className="modal__close" onClick={closeGate} aria-label="닫기">
          ×
        </button>
        <span className={`modal__icon modal__icon--${variant === 'ONBOARDING' ? 'info' : 'heart'}`}>
          {variant === 'ONBOARDING' ? <DocumentIcon /> : <HeartIcon />}
        </span>
        <h2 id="auth-gate-title">{copy.title}</h2>
        <p>{copy.desc}</p>
        <div className="modal__actions">
          {gate === 'LOGIN' ? (
            <>
              <button type="button" className="btn" autoFocus onClick={() => go('/login')}>
                로그인하기
              </button>
              <button type="button" className="btn btn--ghost" onClick={() => go('/signup')}>
                회원가입하기
              </button>
            </>
          ) : (
            <button type="button" className="btn" autoFocus onClick={() => go('/onboarding')}>
              내 조건 입력하기
            </button>
          )}
          <button type="button" className="modal__later" onClick={closeGate}>
            나중에
          </button>
        </div>
      </div>
    </div>
  );
}
