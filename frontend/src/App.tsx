import { Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from './components/ProtectedRoute';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import RuleListPage from './pages/RuleListPage';
import ScenarioListPage from './pages/ScenarioListPage';
import ReviewPage from './pages/ReviewPage';
import ReviewWorkspacePage from './pages/ReviewWorkspacePage';
import ModelConfigPage from './pages/ModelConfigPage';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="rules" element={<RuleListPage />} />
        <Route path="scenarios" element={<ScenarioListPage />} />
        <Route path="review" element={<ReviewPage />} />
        <Route path="review/:taskId" element={<ReviewWorkspacePage />} />
        <Route path="models" element={<ModelConfigPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
