import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { login } from '../api/auth';
import PasswordField from '../components/PasswordField';
import { useAuth } from '../hooks/useAuth';
import { closeGate } from '../hooks/useAuthGate';
import { AuthFooter, AuthHeading } from '../components/AuthCard';
import styles from '../features/auth/AuthPage.module.css';

/** 로그인 팝업·헤더·가입 화면이 넘겨 주는 값 */
export interface AuthNavState {
  /** 로그인 후 돌아갈 경로 */
  from?: string;
  /** 가입 직후 자동 로그인만 실패했을 때 이메일을 채워 준다 */
  email?: string;
  notice?: 'SIGNED_UP';
}

/** 복귀할 곳이 없으면 비회원도 볼 수 있는 지도 탐색 화면으로 보낸다. */
export const DEFAULT_LANDING = '/map';

export default function LoginPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const state = (location.state ?? null) as AuthNavState | null;
  const target = state?.from ?? DEFAULT_LANDING;

  const [email, setEmail] = useState(state?.email ?? '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  // 이미 로그인한 상태로 들어오면 돌아갈 곳(없으면 지도)으로 보낸다.
  if (user) return <Navigate to={target} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      await login(email, password);
      closeGate();
      // 사용자가 바뀌었으니 이전 사용자의 추천·조건 캐시를 버리고 다시 불러온다.
      queryClient.resetQueries();
      navigate(target, { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setFields(err.fields ?? {});
        setError(err.message);
      } else {
        setError('로그인에 실패했습니다.');
      }
      setBusy(false);
    }
  };

  return (
    <form className={styles.panel} onSubmit={submit} aria-busy={busy}>
      <AuthHeading title="로그인" description="집픽에서 나에게 맞는 공공임대주택을 찾아보세요." />

      {state?.notice === 'SIGNED_UP' && (
        <p className={styles.notice} role="status">가입이 완료됐어요. 방금 만든 계정으로 로그인해 주세요.</p>
      )}

      <div className={styles.fields}>
        <label className={styles.field}>
          이메일
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            placeholder="이메일을 입력해 주세요"
            aria-invalid={!!fields.email}
            aria-describedby={fields.email ? 'login-email-error' : undefined}
            required
            autoFocus={!state?.email}
          />
          {fields.email && <small id="login-email-error" className={styles.fieldError}>{fields.email}</small>}
        </label>

        <PasswordField
          label="비밀번호"
          value={password}
          onChange={setPassword}
          placeholder="비밀번호를 입력해 주세요"
          autoComplete="current-password"
          error={fields.password}
        />
      </div>

      {error && <div className={styles.errorNotice} role="alert">{error}</div>}

      <button className={styles.submit} type="submit" disabled={busy}>
        {busy ? '로그인 중…' : '로그인'}
      </button>

      <p className={styles.alternative}>
        아직 계정이 없으신가요?{' '}
        <Link to="/signup" state={state?.from ? { from: state.from } : undefined}>
          회원가입
        </Link>
      </p>
      <AuthFooter />
    </form>
  );
}
