import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { closeGate, returnPath, useAuthGate } from '../hooks/useAuthGate';
import AuthGateIcon from './AuthGateIcon';
import styles from './AuthGateModal.module.css';
import '../design-system/tokens.css';

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
    <div className={`zipick-theme ${styles.modal}`} role="dialog" aria-modal="true" aria-labelledby="auth-gate-title" aria-describedby="auth-gate-description">
      <button type="button" className={styles.backdrop} aria-label="닫기" onClick={closeGate} />
      <div className={styles.card}>
        <button type="button" className={styles.close} onClick={closeGate} aria-label="닫기">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
        </button>
        <span className={styles.icon}>
          <AuthGateIcon kind={variant} />
        </span>
        <h2 id="auth-gate-title" className={styles.title}>{copy.title}</h2>
        <p id="auth-gate-description" className={styles.description}>{copy.desc}</p>
        <div className={styles.actions}>
          {gate === 'LOGIN' ? (
            <>
              <button type="button" className={styles.primary} autoFocus onClick={() => go('/login')}>
                로그인하기
              </button>
              <button type="button" className={styles.secondary} onClick={() => go('/signup')}>
                회원가입하기
              </button>
            </>
          ) : (
            <button type="button" className={styles.primary} autoFocus onClick={() => go('/onboarding')}>
              내 조건 입력하기
            </button>
          )}
          <button type="button" className={styles.later} onClick={closeGate}>
            나중에
          </button>
        </div>
      </div>
    </div>
  );
}
