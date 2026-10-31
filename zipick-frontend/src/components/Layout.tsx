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
        <span className="auth-box__email">{user.email}</span>
        <button
          type="button"
          className={`btn btn--small btn--ghost ${headerStyles.secondary}`}
          onClick={async () => {
            await logout();
            // 다음 사용자에게 이전 사용자의 추천·조건 캐시가 남지 않도록 비우고 다시 불러온다.
            qc.resetQueries();
            closeGate();
            // 개인화 화면에 머물러 있으면 AccessGate가 곧바로 로그인 팝업을 띄운다.
            // 로그아웃 직후에 로그인하라고 조르는 셈이라, 비회원도 볼 수 있는 화면으로 보낸다.
            navigate(DEFAULT_LANDING);
          }}
        >
          로그아웃
        </button>
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

  if (pathname === '/login' || pathname === '/signup') {
    return <><AuthLayout /><AuthGateModal /></>;
  }

  const headerContent = <>
    <NavLink to="/map" className="logo">
      <MapBrand compact={!useServiceTheme} />
    </NavLink>
    {/* 전체 공고와 맞춤 추천은 지도 화면 안에서 전환한다. 독립 메뉴를 두지 않는다. */}
    <nav className="nav">
      <NavLink to="/map" className={headerStyles.navLink}>지도 탐색</NavLink>
      <NavLink to="/calendar" className={headerStyles.navLink}>관심 공고·일정</NavLink>
      <NavLink to="/tune" className={headerStyles.navLink}>취향 찾기</NavLink>
      <NavLink to="/onboarding" className={headerStyles.navLink}>내 조건</NavLink>
    </nav>
    <AuthBox />
  </>;

  return (
    <div className={useServiceTheme ? `app zipick-theme ${mapStyles.frame}` : 'app'}>
      <header className={useServiceTheme ? `header ${headerStyles.header}` : `header ${headerStyles.legacyHeader}`}>
        {useServiceTheme ? <div className={`${headerStyles.inner} ${mapStyles.headerInner}`}>{headerContent}</div> : headerContent}
      </header>
      <main className="main">
        <Outlet />
      </main>
      <AuthGateModal />
    </div>
  );
}
