import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import OnboardingPage from './pages/OnboardingPage';
import RecommendationsPage from './pages/RecommendationsPage';
import HouseDetailPage from './pages/HouseDetailPage';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/recommendations" replace />} />
        <Route path="/onboarding" element={<OnboardingPage />} />
        <Route path="/recommendations" element={<RecommendationsPage />} />
        <Route path="/houses/:houseId" element={<HouseDetailPage />} />
      </Route>
    </Routes>
  );
}
