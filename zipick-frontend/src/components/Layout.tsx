import { useState, type FormEvent } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { login, logout } from '../api/auth';
import { ApiError } from '../api/client';
import { useAuth } from '../hooks/useAuth';

// 임시 로그인 UI. 인증 흐름 확인용이며 정식 로그인 화면이 생기면 이 컴포넌트만 걷어내면 된다.
// 인증 호출과 상태는 api/auth, hooks/useAuth에 있어 그대로 재사용할 수 있다.
function AuthBox() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 사용자가 바뀌면 이전 사용자의 추천·조건 캐시를 버린다.
  // clear() 는 화면에 떠 있는 쿼리를 다시 불러오지 않아 로그인 직후 목록이 비어 보였다 → resetQueries() 로 비우고 다시 불러온다.
  const resetCache = () => qc.resetQueries();

  if (user) {
    return (
      <div className="auth-box">
        <span className="auth-box__email">{user.email}</span>
        <button
          type="button"
          className="btn btn--small btn--ghost"
          onClick={async () => {
            await logout();
            resetCache();
          }}
        >
          로그아웃
        </button>
      </div>
    );
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      setPassword('');
      resetCache();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '로그인에 실패했습니다.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="auth-box" onSubmit={submit}>
      <input
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        placeholder="이메일"
        autoComplete="username"
        required
      />
      <input
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        placeholder="비밀번호"
        autoComplete="current-password"
        required
      />
      <button type="submit" className="btn btn--small" disabled={busy}>
        로그인
      </button>
      {error && <span className="auth-box__error">{error}</span>}
    </form>
  );
}

export default function Layout() {
  return (
    <div className="app">
      <header className="header">
        <NavLink to="/recommendations" className="logo">
          ZIPICK
        </NavLink>
        <nav className="nav">
          <NavLink to="/recommendations">추천</NavLink>
          <NavLink to="/map">지도</NavLink>
          <NavLink to="/calendar">관심 공고·일정</NavLink>
          <NavLink to="/tune">취향 찾기</NavLink>
          <NavLink to="/onboarding">내 조건</NavLink>
        </nav>
        <AuthBox />
      </header>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
