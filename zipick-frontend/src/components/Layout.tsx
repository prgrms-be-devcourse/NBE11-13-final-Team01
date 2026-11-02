import mapStyles from './MapLayout.module.css';
import headerStyles from './Header.module.css';
import '../design-system/tokens.css';
import MapBrand from './MapBrand';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { logout } from '../api/auth';
import { useAuth } from '../hooks/useAuth';
import { closeGate, returnPath } from '../hooks/useAuthGate';
import { DEFAULT_LANDING } from '../pages/LoginPage';
import { usePendingInterestRunner } from '../hooks/useInterest';
import AuthGateModal from './AuthGateModal';
import AuthLayout from './AuthLayout';
import HeaderInner from './HeaderInner';
import AccountMenu from './AccountMenu';

/**
 * 헤더 우측 인증 영역. 비회원에게는 로그인·회원가입 링크만 두고,
 * 입력은 전용 화면(/login, /signup)에서 받는다.
 */
function AuthBox() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const location = useLocation();
  const navigate = useNavigate();

  if (user) {
    return (
      <div className="auth-box">
        {/* 계정 전환·URL 이동(뒤로/앞으로 포함)마다 메뉴만 닫힌 초기 상태로 만든다. */}
        <AccountMenu
          key={`${user.userId}:${location.key}`}
          user={user}
          onLogout={async () => {
            await logout();
            // 다음 사용자에게 이전 사용자의 추천·조건 캐시가 남지 않도록 비우고 다시 불러온다.
            qc.resetQueries();
            closeGate();
            // 개인화 화면에 머물러 있으면 AccessGate가 곧바로 로그인 팝업을 띄운다.
            // 로그아웃 직후에 로그인하라고 조르는 셈이라, 비회원도 볼 수 있는 화면으로 보낸다.
            navigate(DEFAULT_LANDING);
          }}
        />
      </div>
    );
  }

  // 로그인한 뒤 보던 화면으로 돌아오도록 현재 경로를 넘긴다.
  const from = returnPath(location);
  const state = from ? { from } : undefined;

  return (
    <div className="auth-box">
      <Link to="/login" state={state} className={`btn btn--small btn--ghost ${headerStyles.secondary}`}>
        로그인
      </Link>
      <Link to="/signup" state={state} className={`btn btn--small ${headerStyles.primary}`}>
        회원가입
      </Link>
    </div>
  );
}

export default function Layout() {
  // 로그인 직후 보류된 관심 공고 저장을 이어서 실행한다. 앱 전체에서 여기 한 곳만 호출한다.
  usePendingInterestRunner();
  const pathname = useLocation().pathname;
  const useServiceTheme = pathname === '/map' || pathname === '/calendar' || pathname === '/onboarding' || pathname === '/tune';
  const reserveScrollbarGutter = pathname === '/onboarding' || pathname === '/tune';

  if (pathname === '/login' || pathname === '/signup') {
    return <><AuthLayout /><AuthGateModal /></>;
  }

  const headerContent = <>
    <Link to="/landing" className="logo" aria-label="집픽 홈으로 이동">
      <MapBrand />
    </Link>
    {/* 전체 공고와 맞춤 추천은 지도 화면 안에서 전환한다. 독립 메뉴를 두지 않는다. */}
    <nav className="nav" aria-label="주요 메뉴">
      <NavLink to="/map" className={headerStyles.navLink}>
        <svg className={headerStyles.navIcon} width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z" /><circle cx="12" cy="10" r="2.5" />
        </svg>
        <span>지도 탐색</span>
      </NavLink>
      <NavLink to="/calendar" className={headerStyles.navLink}>
        <svg className={headerStyles.navIcon} width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 11h18M8 15h2M14 15h2" />
        </svg>
        <span>관심 공고·일정</span>
      </NavLink>
      <NavLink to="/tune" className={headerStyles.navLink}>
        <svg className={headerStyles.navIcon} width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
          <path d="M4 6h4m4 0h8M4 12h10m4 0h2M4 18h2m4 0h10" /><circle cx="10" cy="6" r="2" /><circle cx="16" cy="12" r="2" /><circle cx="8" cy="18" r="2" />
        </svg>
        <span>취향 찾기</span>
      </NavLink>
    </nav>
    <AuthBox />
  </>;

  return (
    <div
      className={useServiceTheme ? `app zipick-theme ${mapStyles.frame}` : 'app'}
      data-stable-scrollbar={reserveScrollbarGutter ? 'true' : undefined}
    >
      <header className={`header zipick-theme ${headerStyles.header} ${headerStyles.service}`}>
        <HeaderInner>{headerContent}</HeaderInner>
      </header>
      <main className="main">
        <Outlet />
      </main>
      <AuthGateModal />
    </div>
  );
}
