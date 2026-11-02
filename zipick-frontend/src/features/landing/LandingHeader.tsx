import { useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router-dom';
import { logout } from '../../api/auth';
import AccountMenu from '../../components/AccountMenu';
import MapBrand from '../../components/MapBrand';
import HeaderInner from '../../components/HeaderInner';
import headerStyles from '../../components/Header.module.css';
import { useAuth } from '../../hooks/useAuth';
import { closeGate } from '../../hooks/useAuthGate';
import styles from './Landing.module.css';

export default function LandingHeader() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const location = useLocation();

  return (
    <header className={styles['sitehead'] + ' ' + headerStyles.header}>
      <HeaderInner className={styles['head-inner']}>
        <Link className={styles['logo']} to="/landing" aria-label="집픽 홈"><MapBrand /></Link>
        <nav className={styles['menu']} aria-label="주요 메뉴">
          <a className={headerStyles.navLink} href="#features">서비스 소개</a>
          <a className={headerStyles.navLink} href="#journey">이용 방법</a>
          <Link className={headerStyles.navLink} to="/map">지도 탐색</Link>
        </nav>
        <div className={styles['head-actions']}>
          {user ? (
            <div className={styles['account-menu']}>
              <AccountMenu
                key={`${user.userId}:${location.key}`}
                user={user}
                onLogout={async () => {
                  await logout();
                  queryClient.resetQueries();
                  closeGate();
                }}
              />
            </div>
          ) : (
            <Link className={styles['signin'] + ' ' + headerStyles.secondary} to="/login" state={{ from: '/landing' }}>로그인</Link>
          )}
          <Link className={styles['nav-btn'] + ' ' + headerStyles.primary} to="/map">집 찾기 <span className={styles['arrow']}>↗</span></Link>
        </div>
      </HeaderInner>
    </header>
  );
}
