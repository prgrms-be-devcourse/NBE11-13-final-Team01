import { NavLink, Outlet } from 'react-router-dom';

/**
 * 운영자 화면 틀. 왼쪽 메뉴 + 오른쪽 본문. 서버 자원(CPU·메모리·컨테이너)은 Grafana 가 맡으므로
 * 여기서는 서비스 데이터(수집·공고·회원·알림)만 다룬다.
 */
export default function AdminLayout() {
  return (
    <div className="page admin">
      <nav className="admin__nav" aria-label="운영 메뉴">
        <NavLink to="/admin" end>수집 현황</NavLink>
        <NavLink to="/admin/ai-documents">AI 문서 검토</NavLink>
        <NavLink to="/admin/notices">공고·주택</NavLink>
        <NavLink to="/admin/users">회원</NavLink>
        <NavLink to="/admin/notifications">알림 이력</NavLink>
        <NavLink to="/admin/campaigns">소식 캠페인</NavLink>
      </nav>
      <div className="admin__body">
        <Outlet />
      </div>
    </div>
  );
}
