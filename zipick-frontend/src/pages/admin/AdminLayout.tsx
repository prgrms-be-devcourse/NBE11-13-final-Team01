import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { logout } from '../../api/auth';
import MapBrand from '../../components/MapBrand';
import { isAdmin, useAuth } from '../../hooks/useAuth';
import '../../design-system/tokens.css';
import styles from './Admin.module.css';

const MENU = [
  { to: '/admin', label: '수집 현황', end: true },
  { to: '/admin/ai-documents', label: 'AI 문서 검토' },
  { to: '/admin/notices', label: '공고·주택' },
  { to: '/admin/users', label: '회원' },
  { to: '/admin/notifications', label: '알림 이력' },
  { to: '/admin/activity', label: '사용자 행동' },
  { to: '/admin/campaigns', label: '소식 캠페인' },
];

/**
 * 운영자 화면 틀. 서비스 Layout 과 분리된 독립 셸이고, 테마는 이 루트(.zipick-theme)와 Admin.module.css 안에서만 적용된다.
 * 로그인하지 않았거나 운영자가 아니면 /admin/login 으로 보낸다. 권한은 서버(/api/v1/admin/**)가 다시 확인한다.
 * 서버 자원(CPU·메모리·컨테이너)은 Grafana 가 맡으므로 여기서는 서비스 데이터만 다룬다.
 */
export default function AdminLayout() {
  const { user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();

  if (!isAdmin(user)) return <Navigate to="/admin/login" replace state={{ from: location.pathname + location.search }} />;

  return (
    <div className={`zipick-theme ${styles.shell}`}>
      <header className={styles.header}>
        <Link to="/admin" className={styles.brand} aria-label="집픽 운영 홈">
          <MapBrand />
          <span className={styles.brandTag}>운영</span>
        </Link>
        <div className={styles.account}>
          <span className={styles.email}>{user!.email}</span>
          <a className={styles.ghost} href="/map">서비스 보기</a>
          <button
            type="button"
            className={styles.ghost}
            onClick={async () => {
              await logout();
              qc.resetQueries();
              navigate('/admin/login', { replace: true });
            }}
          >
            로그아웃
          </button>
        </div>
      </header>
      <div className={styles.body}>
        <nav className={styles.nav} aria-label="운영 메뉴">
          {MENU.map((m) => (
            <NavLink key={m.to} to={m.to} end={m.end} className={({ isActive }) => (isActive ? styles.navActive : undefined)}>
              {m.label}
            </NavLink>
          ))}
        </nav>
        <main className={styles.content}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
