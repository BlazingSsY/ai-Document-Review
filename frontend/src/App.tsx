import { Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from './components/ProtectedRoute';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import RuleListPage from './pages/RuleListPage';
import ScenarioListPage from './pages/ScenarioListPage';
import ReviewWorkspacePage from './pages/ReviewWorkspacePage';
import ModelConfigPage from './pages/ModelConfigPage';
import ProfilePage from './pages/ProfilePage';
import UserManagementPage from './pages/UserManagementPage';

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
        {/* 智能召回审查（RAG pipeline） */}
        <Route path="rag/scenarios" element={<ScenarioListPage reviewMode="RAG" />} />
        <Route path="rag/rules" element={<RuleListPage reviewMode="RAG" />} />
        {/* 旧 URL 兼容：默认转到全文逐章侧。 */}
        <Route path="scenarios" element={<Navigate to="/chunk/scenarios" replace />} />
        <Route path="rules" element={<Navigate to="/chunk/rules" replace />} />
        <Route path="review" element={<Navigate to="/dashboard" replace />} />
        <Route path="review/:taskId" element={<ReviewWorkspacePage />} />
        <Route path="models" element={<ModelConfigPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="users" element={<UserManagementPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
