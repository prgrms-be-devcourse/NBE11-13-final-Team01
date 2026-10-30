import LandingPage from './pages/LandingPage';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import Layout from './components/Layout';
import AccessGate from './components/AccessGate';
import OnboardingPage from './pages/OnboardingPage';
import HouseDetailPage from './pages/HouseDetailPage';
import MapPage from './pages/MapPage';
import CalendarPage from './pages/CalendarPage';
import ApplicationGuidePage from './pages/ApplicationGuidePage';
import TunePage from './pages/TunePage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import AdminCampaignPage from './pages/AdminCampaignPage';
import AdminLayout from './pages/admin/AdminLayout';
import AdminLoginPage from './pages/admin/AdminLoginPage';
import AdminDashboardPage from './pages/admin/AdminDashboardPage';
import AdminAiDocumentsPage from './pages/admin/AdminAiDocumentsPage';
import AdminNoticesPage from './pages/admin/AdminNoticesPage';
import AdminUsersPage from './pages/admin/AdminUsersPage';
import AdminNotificationsPage from './pages/admin/AdminNotificationsPage';

/**
 * 탐색은 지도 한 화면에서 한다. 전체 공고 / 맞춤 추천은 별도 페이지가 아니라 `?mode=` 로 구분되는 두 모드다.
 * 비회원도 지도·필터·공고 상세를 볼 수 있고, 개인화 기능에서만 AccessGate·공통 팝업으로 막는다.
 *
 * 기존 /houses, /recommendations 는 지도 모드로 넘긴다(필터 쿼리는 그대로 넘겨 준다).
 * 두 페이지 컴포넌트(AllHousesPage, RecommendationsPage)는 지우지 않고 라우팅에서만 뺐다.
 */

/** 기존 목록 URL을 지도 모드로 넘긴다. 필터 쿼리는 그대로 유지한다. */
function RedirectToMap({ mode }: { mode?: 'recommended' }) {
  const { search } = useLocation();
  const params = new URLSearchParams(search);
  if (mode) params.set('mode', mode);
  else params.delete('mode');
  const query = params.toString();
  return <Navigate to={query ? `/map?${query}` : '/map'} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/landing" element={<LandingPage />} />
      {/*
        운영자 화면. 서비스 Layout 과 분리된 독립 셸(AdminLayout)이고 로그인도 따로(/admin/login, ROLE_ADMIN 만).
        AdminLayout 이 운영자가 아니면 /admin/login 으로 보내고, 서버도 /api/v1/admin/** 를 ROLE_ADMIN 으로 막는다.
      */}
      <Route path="/admin/login" element={<AdminLoginPage />} />
      <Route path="/admin" element={<AdminLayout />}>
        <Route index element={<AdminDashboardPage />} />
        <Route path="ai-documents" element={<AdminAiDocumentsPage />} />
        <Route path="notices" element={<AdminNoticesPage />} />
        <Route path="users" element={<AdminUsersPage />} />
        <Route path="notifications" element={<AdminNotificationsPage />} />
        <Route path="campaigns" element={<AdminCampaignPage />} />
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Route>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/landing" replace />} />

        {/* 비회원도 볼 수 있는 화면 */}
        <Route path="/map" element={<MapPage />} />
        <Route path="/houses" element={<RedirectToMap />} />
        {/* 상세는 공개 정보 + 개인화 영역으로 나뉜다. 게이트는 페이지 안에서 점수 영역만 막는다. */}
        <Route path="/houses/:houseId" element={<HouseDetailPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />

        {/* 로그인만 필요 */}
        <Route
          path="/onboarding"
          element={
            <AccessGate need="LOGIN">
              <OnboardingPage />
            </AccessGate>
          }
        />
        <Route
          path="/calendar"
          element={
            <AccessGate need="LOGIN">
              <CalendarPage />
            </AccessGate>
          }
        />
        {/*
          AI 신청 준비 가이드. 관심 공고로 저장한 공고에서만 쓸 수 있어 관심 공고 경로 아래에 둔다.
          결과가 길고 서류를 준비하는 동안 열어 두고 보는 화면이라 모달·패널이 아니라 별도 페이지다.
          관심 저장 여부는 서버가 다시 확인한다(여기 게이트는 로그인까지만).
        */}
        <Route
          path="/interest-notices/:noticeId/guide"
          element={
            <AccessGate need="LOGIN">
              <ApplicationGuidePage />
            </AccessGate>
          }
        />


        {/* 맞춤 추천은 지도 모드로. 로그인·온보딩 확인은 지도 화면이 직접 한다 */}
        <Route path="/recommendations" element={<RedirectToMap mode="recommended" />} />

        {/* 로그인 + 내 조건 입력까지 필요 */}
        <Route
          path="/tune"
          element={
            <AccessGate need="ONBOARDING">
              <TunePage />
            </AccessGate>
          }
        />
      </Route>
    </Routes>
  );
}
