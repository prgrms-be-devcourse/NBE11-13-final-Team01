import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { login } from '../api/auth';
import PasswordField from '../components/PasswordField';
import { useAuth } from '../hooks/useAuth';
import { closeGate } from '../hooks/useAuthGate';

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
    <div className="auth-page">
      <form className="auth-card" onSubmit={submit}>
        <Link to="/map" className="auth-card__logo">
          ZIPICK
        </Link>
        <h1 className="auth-card__title">로그인</h1>
        <p className="auth-card__lead">집픽과 함께 나에게 맞는 주택을 찾아보세요.</p>

        {state?.notice === 'SIGNED_UP' && (
          <p className="auth-notice">가입이 완료됐어요. 방금 만든 계정으로 로그인해 주세요.</p>
        )}

        <label>
          이메일
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            placeholder="이메일을 입력하세요"
            required
            autoFocus={!state?.email}
          />
          {fields.email && <small className="auth-error">{fields.email}</small>}
        </label>

        <PasswordField
          label="비밀번호"
          value={password}
          onChange={setPassword}
          placeholder="비밀번호를 입력하세요"
          autoComplete="current-password"
          error={fields.password}
        />

        {error && <div className="error">{error}</div>}

        <button className="btn auth-card__submit" type="submit" disabled={busy}>
          {busy ? '로그인 중…' : '로그인'}
        </button>

        <p className="auth-alt">
          아직 계정이 없으신가요?{' '}
          <Link to="/signup" state={state?.from ? { from: state.from } : undefined}>
            회원가입
          </Link>
        </p>
      </form>
    </div>
  );
}
