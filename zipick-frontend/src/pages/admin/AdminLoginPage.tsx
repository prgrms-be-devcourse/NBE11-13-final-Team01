import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../../api/client';
import { adminLogin, logout } from '../../api/auth';
import PasswordField from '../../components/PasswordField';
import MapBrand from '../../components/MapBrand';
import { isAdmin, useAuth } from '../../hooks/useAuth';
import '../../design-system/tokens.css';
import styles from './Admin.module.css';

/**
 * 운영자 전용 로그인. 서버의 /api/auth/admin/login 은 ROLE_ADMIN 이 아니면 토큰을 주지 않는다.
 * 서비스 로그인(/login)과 화면·흐름을 나눠, 일반 사용자 세션이 운영 화면으로 이어지지 않게 한다.
 */
export default function AdminLoginPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const from = (location.state as { from?: string } | null)?.from ?? '/admin';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  if (isAdmin(user)) return <Navigate to={from.startsWith('/admin') ? from : '/admin'} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setPending(true);
    try {
      // 서비스 쪽 일반 계정으로 로그인돼 있으면 먼저 끊는다 (운영 세션과 섞지 않는다)
      if (user) await logout();
      await adminLogin(email.trim(), password);
      qc.resetQueries();
      navigate(from.startsWith('/admin') ? from : '/admin', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401
        ? '운영자 계정이 아니거나 이메일·비밀번호가 올바르지 않습니다.'
        : err instanceof Error ? err.message : '로그인에 실패했어요.');
    } finally {
      setPending(false);
    }
  };

  return (
    <div className={`zipick-theme ${styles.login}`}>
      <form className={styles.loginCard} onSubmit={submit} aria-labelledby="admin-login-title">
        <div className={styles.brand}>
          <MapBrand />
          <span className={styles.brandTag}>운영</span>
        </div>
        <h1 id="admin-login-title" className={styles.loginTitle}>운영자 로그인</h1>
        <p className={styles.loginHint}>운영자(ROLE_ADMIN) 계정만 로그인할 수 있어요.</p>
        {user && !isAdmin(user) && (
          <p className={styles.loginNotice}>지금 {user.email} 로 로그인돼 있어요. 운영자 계정으로 로그인하면 이 세션은 로그아웃됩니다.</p>
        )}
        <label className={styles.field}>
          <span>이메일</span>
          <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <div className={styles.field}>
          <PasswordField label="비밀번호" value={password} onChange={setPassword} autoComplete="current-password" />
        </div>
        {error && <p className={styles.loginError} role="alert">{error}</p>}
        <button type="submit" className={styles.primary} disabled={pending || !email || !password}>
          {pending ? '확인 중…' : '로그인'}
        </button>
        <a className={styles.backLink} href="/map">서비스로 돌아가기</a>
      </form>
    </div>
  );
}
