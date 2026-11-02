import { Link, Outlet } from 'react-router-dom';
import MapBrand from './MapBrand';
import styles from '../features/auth/AuthPage.module.css';
import headerStyles from './Header.module.css';
import '../design-system/tokens.css';
import HeaderInner from './HeaderInner';

/** 인증 화면의 시각적 셸. 인증 게이트와 보류 작업은 상위 Layout에서 유지한다. */
export default function AuthLayout() {
  return (
    <div className={`zipick-theme ${styles.frame}`}>
      <header className={`${styles.header} ${headerStyles.header} ${headerStyles.authentication}`}>
        <HeaderInner className={styles.headerInner}>
          <Link to="/landing" className={styles.headerBrand} aria-label="집픽 홈으로 이동">
            <MapBrand />
          </Link>
          <Link to="/map" className={`${styles.mapButton} ${headerStyles.secondary}`}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="m12 19-7-7 7-7M5 12h14" />
            </svg>
            지도 둘러보기
          </Link>
        </HeaderInner>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  );
}
