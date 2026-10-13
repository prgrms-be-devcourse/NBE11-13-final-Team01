import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import OnboardingPage from './pages/OnboardingPage';
import RecommendationsPage from './pages/RecommendationsPage';
import HouseDetailPage from './pages/HouseDetailPage';
import MapPage from './pages/MapPage';
import CalendarPage from './pages/CalendarPage';
import TunePage from './pages/TunePage';
import AllHousesPage from './pages/AllHousesPage';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/recommendations" replace />} />
        <Route path="/onboarding" element={<OnboardingPage />} />
        <Route path="/recommendations" element={<RecommendationsPage />} />
        <Route path="/houses" element={<AllHousesPage />} />
        <Route path="/houses/:houseId" element={<HouseDetailPage />} />
        <Route path="/tune" element={<TunePage />} />
        {/* 아래 두 화면은 자리만 잡아 둔 것. 본체는 src/features/map, src/features/calendar */}
        <Route path="/map" element={<MapPage />} />
        <Route path="/calendar" element={<CalendarPage />} />
      </Route>
    </Routes>
  );
}
