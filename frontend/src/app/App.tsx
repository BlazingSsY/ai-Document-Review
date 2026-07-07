import { Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from '../shared/components/ProtectedRoute';
import AppLayout from '../shared/components/AppLayout';
import LoginPage from '../features/auth/pages/LoginPage';
import DashboardPage from '../features/dashboard/pages/DashboardPage';
import RuleListPage from '../features/rules/pages/RuleListPage';
import ScenarioListPage from '../features/scenarios/pages/ScenarioListPage';
import ReviewWorkspacePage from '../features/review/pages/ReviewWorkspacePage';
import ModelConfigPage from '../features/modelConfig/pages/ModelConfigPage';
import DataBoardPage from '../features/dashboard/pages/DataBoardPage';
import ProfilePage from '../features/users/pages/ProfilePage';
import UserManagementPage from '../features/users/pages/UserManagementPage';

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
        {/* 全文逐章审查（chunk pipeline） */}
        <Route path="chunk/scenarios" element={<ScenarioListPage reviewMode="CHUNK" />} />
        <Route path="chunk/rules" element={<RuleListPage reviewMode="CHUNK" />} />
        {/* 结构化审查（SAR pipeline） */}
        <Route path="sar/scenarios" element={<ScenarioListPage reviewMode="SAR" />} />
        <Route path="sar/rules" element={<RuleListPage reviewMode="SAR" />} />
        {/* 旧 URL 兼容：默认转到全文逐章侧。 */}
        <Route path="scenarios" element={<Navigate to="/chunk/scenarios" replace />} />
        <Route path="rules" element={<Navigate to="/chunk/rules" replace />} />
        <Route path="review" element={<Navigate to="/dashboard" replace />} />
        <Route path="review/:taskId" element={<ReviewWorkspacePage />} />
        <Route path="models" element={<ModelConfigPage />} />
        <Route path="analytics" element={<DataBoardPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="users" element={<UserManagementPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
