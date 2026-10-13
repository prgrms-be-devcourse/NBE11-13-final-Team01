import { NavLink, Outlet } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useCurrentUser } from '../hooks/useCurrentUser';

export default function Layout() {
  const { userId, setUserId } = useCurrentUser();
  const qc = useQueryClient();

  return (
    <div className="app">
      <header className="header">
        <NavLink to="/recommendations" className="logo">
          ZIPICK
        </NavLink>
        <nav className="nav">
          <NavLink to="/recommendations">추천</NavLink>
          <NavLink to="/map">지도</NavLink>
          <NavLink to="/calendar">일정</NavLink>
          <NavLink to="/tune">취향 찾기</NavLink>
          <NavLink to="/onboarding">내 조건</NavLink>
        </nav>
        <label className="dev-user" title="로그인 연동 전 임시 사용자 전환">
          사용자
          <select
            value={userId}
            onChange={(e) => {
              setUserId(Number(e.target.value));
              qc.clear();
            }}
          >
            <option value={1}>1 · 지우 (27세 1인)</option>
            <option value={2}>2 · 현우 (31세 기혼)</option>
          </select>
        </label>
      </header>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
