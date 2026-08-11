import { Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from '../shared/components/ProtectedRoute';
import FeatureProtectedRoute from '../shared/components/FeatureProtectedRoute';
import ManagerProtectedRoute from '../shared/components/ManagerProtectedRoute';
import AppLayout from '../shared/components/AppLayout';
import LoginPage from '../features/auth/pages/LoginPage';
import DashboardPage from '../features/dashboard/pages/DashboardPage';
import RuleListPage from '../features/rules/pages/RuleListPage';
import ScenarioListPage from '../features/scenarios/pages/ScenarioListPage';
import ReviewWorkspacePage from '../features/review/pages/ReviewWorkspacePage';
import ModelConfigPage from '../features/modelConfig/pages/ModelConfigPage';
import DataBoardPage from '../features/dashboard/pages/DataBoardPage';
import ProfilePage from '../features/users/pages/ProfilePage';
import MemberManagementPage from '../features/users/pages/MemberManagementPage';

const ENV_REVIEW_FEATURE = 'ENV_TEST_OUTLINE_REVIEW';

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
        <Route path="chunk/scenarios" element={(
          <FeatureProtectedRoute featureCode={ENV_REVIEW_FEATURE}>
            <ScenarioListPage reviewMode="CHUNK" />
          </FeatureProtectedRoute>
        )} />
        <Route path="chunk/rules" element={(
          <FeatureProtectedRoute featureCode={ENV_REVIEW_FEATURE}>
            <RuleListPage reviewMode="CHUNK" />
          </FeatureProtectedRoute>
        )} />
        {/* 结构化审查暂不在前端开放；旧地址统一回到全文逐章审查。 */}
        <Route path="sar/scenarios" element={<Navigate to="/chunk/scenarios" replace />} />
        <Route path="sar/rules" element={<Navigate to="/chunk/rules" replace />} />
        {/* 旧 URL 兼容：默认转到全文逐章侧。 */}
        <Route path="scenarios" element={<Navigate to="/chunk/scenarios" replace />} />
        <Route path="rules" element={<Navigate to="/chunk/rules" replace />} />
        <Route path="review" element={<Navigate to="/dashboard" replace />} />
        <Route path="review/:taskId" element={(
          <FeatureProtectedRoute featureCode={ENV_REVIEW_FEATURE}>
            <ReviewWorkspacePage />
          </FeatureProtectedRoute>
        )} />
        <Route path="models" element={<ModelConfigPage />} />
        <Route path="analytics" element={<DataBoardPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="members" element={(
          <ManagerProtectedRoute><MemberManagementPage /></ManagerProtectedRoute>
        )} />
        {/* 原“用户管理”已并入成员管理，保留旧地址只用于书签兼容。 */}
        <Route path="users" element={<Navigate to="/members" replace />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
