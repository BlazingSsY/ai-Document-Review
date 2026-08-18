import { Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from '../shared/components/ProtectedRoute';
import ReviewFeatureRoute from '../shared/components/ReviewFeatureRoute';
import ManagerProtectedRoute from '../shared/components/ManagerProtectedRoute';
import AppLayout from '../shared/components/AppLayout';
import LoginPage from '../features/auth/pages/LoginPage';
import DashboardPage from '../features/dashboard/pages/DashboardPage';
import RuleListPage from '../features/rules/pages/RuleListPage';
import ScenarioListPage from '../features/scenarios/pages/ScenarioListPage';
import ReviewTaskCenterPage from '../features/review/pages/ReviewTaskCenterPage';
import ReviewWorkspacePage from '../features/review/pages/ReviewWorkspacePage';
import ModelConfigPage from '../features/modelConfig/pages/ModelConfigPage';
import DataBoardPage from '../features/dashboard/pages/DataBoardPage';
import ProfilePage from '../features/users/pages/ProfilePage';
import MemberManagementPage from '../features/users/pages/MemberManagementPage';
import {
  DEFAULT_REVIEW_FEATURE, reviewFeaturePath,
} from '../features/review/registry/reviewFeatures';

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

        {/* 各审查业务域共用一条参数化路由：业务域由 :slug 解析，页面自行校验授权。
            新增审查功能只需往 REVIEW_FEATURES 加一条，这里无需改动。 */}
        <Route path="reviews/:slug" element={<ReviewTaskCenterPage />} />
        {/* 详情页跨业务域共用，只要求「有审查功能」；任务归属由后端按 userId 校验。 */}
        <Route path="review/:taskId" element={(
          <ReviewFeatureRoute><ReviewWorkspacePage /></ReviewFeatureRoute>
        )} />

        {/* 审查配置：场景与规则，供 usesSharedRuleLibraries 的业务域共用 */}
        <Route path="chunk/scenarios" element={(
          <ReviewFeatureRoute><ScenarioListPage /></ReviewFeatureRoute>
        )} />
        <Route path="chunk/rules" element={(
          <ReviewFeatureRoute><RuleListPage /></ReviewFeatureRoute>
        )} />

        <Route path="models" element={<ModelConfigPage />} />
        <Route path="analytics" element={<DataBoardPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="members" element={(
          <ManagerProtectedRoute><MemberManagementPage /></ManagerProtectedRoute>
        )} />

        {/* 旧地址兼容：结构化审查未在前端开放，用户管理已并入成员管理，
            按管线命名的旧任务中心地址改为按业务域命名。 */}
        <Route path="sar/scenarios" element={<Navigate to="/chunk/scenarios" replace />} />
        <Route path="sar/rules" element={<Navigate to="/chunk/rules" replace />} />
        <Route path="scenarios" element={<Navigate to="/chunk/scenarios" replace />} />
        <Route path="rules" element={<Navigate to="/chunk/rules" replace />} />
        <Route
          path="chunk/review"
          element={<Navigate to={reviewFeaturePath(DEFAULT_REVIEW_FEATURE)} replace />}
        />
        <Route
          path="review"
          element={<Navigate to={reviewFeaturePath(DEFAULT_REVIEW_FEATURE)} replace />}
        />
        <Route path="users" element={<Navigate to="/members" replace />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
