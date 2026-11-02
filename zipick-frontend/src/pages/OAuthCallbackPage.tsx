import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { abortKakaoLogin, finishKakaoLogin } from '../api/kakaoAuth';
import { closeGate } from '../hooks/useAuthGate';
import { DEFAULT_LANDING } from './LoginPage';
import styles from '../features/auth/AuthPage.module.css';

/**
 * 카카오 콜백 처리 화면.
 *
 * 인가 코드는 1회용이라 두 번 보내면 두 번째가 반드시 실패한다.
 * StrictMode의 effect 이중 실행과 뒤로가기 재진입을 모두 막기 위해
 * 이미 처리한 코드를 모듈 범위에 기록해 둔다(컴포넌트가 다시 마운트돼도 남는다).
 */
const handled = new Set<string>();

export default function OAuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const code = params.get('code');
    const state = params.get('state');
    // 사용자가 동의 화면에서 취소하면 code 대신 error 가 온다.
    const denied = params.get('error');

    if (denied || !code || !state) {
      const { from } = abortKakaoLogin();
      navigate('/login', {
        replace: true,
        state: { from, notice: 'KAKAO_CANCELED' },
      });
      return;
    }

    if (handled.has(code)) return;
    handled.add(code);

    finishKakaoLogin(code, state)
      .then(({ from }) => {
        closeGate();
        // 사용자가 바뀌었으니 이전 사용자의 추천·조건 캐시를 버리고 다시 불러온다.
        queryClient.resetQueries();
        // replace 로 이동해 인가 코드가 붙은 주소를 뒤로가기 기록에서 지운다.
        navigate(from ?? DEFAULT_LANDING, { replace: true });
      })
      .catch((err) => {
        setError(err instanceof ApiError ? err.message : '카카오 로그인에 실패했습니다.');
      });
  }, [params, navigate, queryClient]);

  return (
    <div className={`zipick-theme ${styles.frame}`}>
      <main className={styles.main}>
        <div className={styles.panel}>
          {error ? (
            <>
              <h1 className={styles.callbackTitle}>로그인하지 못했어요</h1>
              <div className={styles.errorNotice} role="alert">{error}</div>
              <button
                type="button"
                className={styles.submit}
                onClick={() => navigate('/login', { replace: true })}
              >
                로그인 화면으로 돌아가기
              </button>
            </>
          ) : (
            <p className={styles.callbackStatus} role="status">카카오 로그인을 마무리하는 중이에요…</p>
          )}
        </div>
      </main>
    </div>
  );
}
