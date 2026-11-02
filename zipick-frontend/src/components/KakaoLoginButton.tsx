import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { startKakaoLogin } from '../api/kakaoAuth';
import { peekPending } from '../hooks/useAuthGate';
import type { AuthNavState } from '../pages/LoginPage';
import styles from '../features/auth/AuthPage.module.css';

/** 카카오 로그인 심벌(말풍선). 공식 버튼 가이드의 노란 배경 + 검정 심벌·문구를 따른다. */
function KakaoSymbol() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true" focusable="false">
      <path
        fill="currentColor"
        d="M9 1.5c-4.15 0-7.5 2.64-7.5 5.9 0 2.09 1.38 3.92 3.46 4.96l-.88 3.23c-.08.28.24.5.48.34l3.87-2.56c.19.01.38.02.57.02 4.15 0 7.5-2.64 7.5-5.9S13.15 1.5 9 1.5Z"
      />
    </svg>
  );
}

/**
 * 카카오 로그인 시작 버튼. 로그인·회원가입 화면에서 같이 쓴다.
 *
 * 누르면 현재 화면의 복귀 경로와 보류 작업(관심 공고 저장 등)을 함께 맡겨 두고
 * 카카오 동의 화면으로 떠난다. 돌아오는 처리는 OAuthCallbackPage 가 맡는다.
 */
export default function KakaoLoginButton({ disabled }: { disabled?: boolean }) {
  const location = useLocation();
  const state = (location.state ?? null) as AuthNavState | null;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      // 이 호출이 성공하면 페이지가 카카오로 떠나므로 busy 를 되돌리지 않는다.
      await startKakaoLogin({ from: state?.from, pending: peekPending() });
    } catch {
      setError('카카오 로그인을 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      setBusy(false);
    }
  };

  return (
    <>
      <div className={styles.socialDivider}><span>또는</span></div>
      <button type="button" className={styles.kakaoButton} onClick={start} disabled={busy || disabled}>
        <KakaoSymbol />
        {busy ? '카카오로 이동 중…' : '카카오로 시작하기'}
      </button>
      {error && <div className={styles.errorNotice} role="alert">{error}</div>}
    </>
  );
}
